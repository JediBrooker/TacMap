package com.tacmap.calibration

import com.tacmap.BuildConfig

/**
 * The ArcGIS Location Platform key for the Esri basemap, and whether we have one.
 *
 * Compiled in from build.gradle.kts (local.properties -> gradle prop -> env).
 * Not a secret - it ships in the APK and is a quota/billing control. See the
 * build script comment for why.
 *
 * When the key is absent the Esri basemap is simply unavailable: we never fall
 * back to the old unauthenticated server.arcgisonline.com endpoint, because
 * hot-linking that is exactly the licensing problem the key exists to fix.
 */
object EsriKey {
    val token: String get() = BuildConfig.ESRI_API_KEY
    val isAvailable: Boolean get() = token.isNotBlank()
}
