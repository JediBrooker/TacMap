package com.tacmap.map

import com.tacmap.BuildConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Fails the build 60 days before the Esri API key expires.
 *
 * The key ships in the binary and has a hard expiry (currently 2027-06-30).
 * When it lapses the Esri basemap just starts returning 401 in the field, with
 * no warning. A field mapping tool should never fail silently, and "renew the
 * map key" is not something you want to discover from a user. So this test is
 * the alarm: it goes red in CI and local test runs while there's still two
 * months to rotate the key and ship an update.
 *
 * When you rotate: update ESRI_KEY_EXPIRY in app/build.gradle.kts (and the iOS
 * Info.plist ESRIKeyExpiry to match), drop the new key into local.properties,
 * ship. This test goes green again.
 *
 * If the key is empty (dev machine with no key configured) there's nothing to
 * expire, so we skip - an absent key disables the Esri basemap, it doesn't 401.
 */
class EsriKeyExpiryTest {

    @Test fun keyIsNotWithin60DaysOfExpiry() {
        if (BuildConfig.ESRI_API_KEY.isBlank()) return // no key, nothing to expire

        val expiry = LocalDate.parse(BuildConfig.ESRI_KEY_EXPIRY)
        val daysLeft = ChronoUnit.DAYS.between(LocalDate.now(), expiry)
        assertTrue(
            "Esri API key expires $expiry ($daysLeft days). Rotate it: new key in " +
                "local.properties, bump ESRI_KEY_EXPIRY in build.gradle.kts + the iOS " +
                "Info.plist ESRIKeyExpiry, ship an update.",
            daysLeft > 60
        )
    }
}
