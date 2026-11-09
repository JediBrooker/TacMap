package com.tacmap.billing

import com.tacmap.calibration.BasemapStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide "is this the full version" switch. MainActivity owns the truth
 * (purchase or trial still running) and pushes it here, everything else just
 * reads the flow. Defaults to true so unit tests and anything composed before
 * the activity has worked it out behave like the paid app.
 *
 * 3.1: the app never hard-locks anymore. false here means "free viewer", not
 * "blocked".
 */
object FeatureAccess {
    private val _isFullVersion = MutableStateFlow(true)
    val isFullVersion: StateFlow<Boolean> = _isFullVersion

    fun set(fullVersion: Boolean) {
        _isFullVersion.value = fullVersion
    }
}

/**
 * Pure rules for what the free viewer may touch. No Android deps so the
 * free/locked list is unit testable, and so MapScreen, LayersSheet and the
 * view model all agree on the same answer.
 */
object FeatureGate {
    /** the things the viewer locks behind the paywall, titles come from the menu rows */
    enum class Feature {
        SYMBOLOGY,
        DRAWINGS,
        MEASURE,
        WEATHER,
        IMPORT_GEOJSON,
        IMPORT_KML,
        EXPORT,
        TRACK_RECORDING,
        CHAT,
        UNIT_SYNC,
        ESRI_BASEMAP,
    }

    /** everything in [Feature] is paid; this exists so call sites don't hand roll the check */
    fun allows(feature: Feature, fullVersion: Boolean): Boolean = fullVersion

    /**
     * Which online style actually goes on screen for [style]. Esri styles need
     * both the paid app and a build time key, otherwise OpenTopoMap stands in.
     * The caller keeps its stored preference untouched so the Esri map comes
     * straight back on unlock.
     */
    fun displayStyle(style: BasemapStyle, fullVersion: Boolean, esriKeyAvailable: Boolean): BasemapStyle {
        if (!style.requiresEsriKey) return style
        return if (fullVersion && esriKeyAvailable) style else BasemapStyle.OSM_TOPO
    }

    /** true when the Layers row for [style] should show the lock and open the paywall */
    fun basemapLocked(style: BasemapStyle, fullVersion: Boolean): Boolean =
        style.requiresEsriKey && !fullVersion
}
