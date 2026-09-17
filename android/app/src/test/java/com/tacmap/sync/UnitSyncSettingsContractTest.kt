package com.tacmap.sync

import com.tacmap.settings.BackgroundUnitSyncInterval
import com.tacmap.settings.OpsecSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UnitSyncSettingsContractTest {
    @Test
    fun backgroundLocationDefaultsOffAndCadenceChoicesRoundTripByMinutes() {
        assertFalse(OpsecSettings.DEFAULT_BACKGROUND_UNIT_SYNC_LOCATION)
        assertEquals(BackgroundUnitSyncInterval.FIFTEEN_MINUTES, BackgroundUnitSyncInterval.DEFAULT)
        assertEquals(
            listOf(1, 5, 15, 30, 60),
            BackgroundUnitSyncInterval.entries.map { it.minutes },
        )
        assertEquals(
            listOf(
                "Every minute",
                "Every 5 minutes",
                "Every 15 minutes",
                "Every 30 minutes",
                "Every 60 minutes",
            ),
            BackgroundUnitSyncInterval.entries.map { it.displayName },
        )

        BackgroundUnitSyncInterval.entries.forEach { interval ->
            assertEquals(interval, BackgroundUnitSyncInterval.fromPersisted(interval.minutes))
        }
        assertEquals(
            BackgroundUnitSyncInterval.DEFAULT,
            BackgroundUnitSyncInterval.fromPersisted(-1),
        )
    }

    @Test
    fun v3RequiresConsentUntilBothLocationSettingsAreEnabledButV2NeverDoes() {
        assertTrue(UnitSyncJoinGate.requiresConsent("3:room", false, false))
        assertTrue(UnitSyncJoinGate.requiresConsent(" 3:room ", true, false))
        assertTrue(UnitSyncJoinGate.requiresConsent("3:room", false, true))
        assertFalse(UnitSyncJoinGate.requiresConsent("3:room", true, true))

        assertFalse(UnitSyncJoinGate.requiresConsent("2:legacy", false, false))
        assertFalse(UnitSyncJoinGate.requiresConsent("invalid", false, false))
    }

    @Test
    fun consentCopyNamesBothPersistentControlsAndSelectedCadence() {
        val message = unitSyncJoinConsentMessage(BackgroundUnitSyncInterval.THIRTY_MINUTES)

        assertTrue(message.contains("Share my location"))
        assertTrue(message.contains("Background Unit Sync location"))
        assertTrue(message.contains("When Location access is allowed"))
        assertTrue(message.contains("approximately every 30 minutes while the screen is off"))
    }

    @Test
    fun productionUiExposesAndPersistsTheSettingsBeforeJoining() {
        val settings = sourceText(
            "android/app/src/main/java/com/tacmap/settings/OpsecSettings.kt"
        )
        val opsecDialog = sourceText(
            "android/app/src/main/java/com/tacmap/map/OpsecSettingsDialog.kt"
        )
        val syncDialog = sourceText(
            "android/app/src/main/java/com/tacmap/sync/SyncDialog.kt"
        )
        val syncManager = sourceText(
            "android/app/src/main/java/com/tacmap/sync/SyncManager.kt"
        )
        val durableCommit = sourceText(
            "android/app/src/main/java/com/tacmap/util/DurablePreferenceCommit.kt"
        )

        assertTrue(settings.contains("putBoolean(key, value)"))
        assertTrue(settings.contains("putInt(KEY_BACKGROUND_UNIT_SYNC_INTERVAL_MINUTES, value.minutes)"))
        assertTrue(settings.contains("DurablePreferenceCommit.preferences"))
        assertTrue(durableCommit.contains("commit = { preferences.edit().let(mutate).commit() }"))
        assertTrue(durableCommit.contains("rollback = { snapshot -> snapshot.restore(preferences) }"))
        assertTrue(durableCommit.contains("editor.apply()"))
        assertTrue(opsecDialog.contains("\"Background Unit Sync location\""))
        assertTrue(opsecDialog.contains("\"Screen-off update interval\""))
        assertTrue(opsecDialog.contains("enabled = backgroundUnitSyncLocation"))
        assertTrue(opsecDialog.contains("near-real-time (about every 5 seconds)"))
        assertTrue(opsecDialog.contains("only screen-off background updates"))

        val consentBlock = syncDialog.indexOf("pendingJoinCode?.let")
        val persistBackground = syncDialog.indexOf(
            "opsec.setBackgroundUnitSyncLocation(true)",
            startIndex = consentBlock,
        )
        val enableShare = syncDialog.indexOf("shareLocation = true", startIndex = persistBackground)
        val persistShare = syncDialog.indexOf("if (!commitConfig())", startIndex = enableShare)
        val join = syncDialog.indexOf("manager.join(pendingCode)", startIndex = persistShare)
        assertTrue(consentBlock >= 0)
        assertTrue(enableShare >= 0)
        assertTrue(enableShare > persistBackground)
        assertTrue(persistShare > enableShare)
        assertTrue(join > persistShare)
        assertTrue(syncDialog.contains("Text(L10n.text(\"Enable & Join\"))"))
        assertTrue(syncDialog.contains("Text(L10n.text(\"Cancel\"))"))
        assertTrue(syncManager.contains("@Volatile\n    var presenceConfig"))
        assertTrue(syncManager.contains("synchronized(presenceConfigLock)"))
        assertTrue(syncManager.contains("sendPresenceFrameIfConfigCurrent(cfg, frame)"))
    }

    @Test
    fun screenOffRuntimeAndLocationForegroundServiceAreWiredFailClosed() {
        val activity = sourceText(
            "android/app/src/main/java/com/tacmap/app/MainActivity.kt"
        )
        val mapScreen = sourceText(
            "android/app/src/main/java/com/tacmap/map/MapScreen.kt"
        )
        val manifest = sourceText("android/app/src/main/AndroidManifest.xml")
        val service = sourceText(
            "android/app/src/main/java/com/tacmap/sync/BackgroundUnitSyncLocationService.kt"
        )
        val manager = sourceText(
            "android/app/src/main/java/com/tacmap/sync/SyncManager.kt"
        )

        val pause = activity.indexOf("unitSyncRuntime.onActivityPausing()")
        val keyLock = activity.indexOf("DataKey.lock()", startIndex = pause)
        assertTrue(pause >= 0)
        assertTrue(keyLock > pause)
        assertTrue(mapScreen.contains("unitSyncRuntime.acquireForeground("))
        assertTrue(mapScreen.contains("DisposableEffect(unitSyncLease)"))
        assertTrue(mapScreen.contains("unitSyncRuntime.releaseScreen(unitSyncLease)"))

        assertTrue(manifest.contains("com.tacmap.sync.BackgroundUnitSyncLocationService"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"location\""))
        assertFalse(manifest.contains("android.permission.ACCESS_BACKGROUND_LOCATION"))
        assertTrue(service.contains("return START_NOT_STICKY"))
        assertTrue(service.contains("LocationManager.GPS_PROVIDER"))
        assertTrue(service.contains("Stop sharing"))

        assertTrue(manager.contains("if (backgroundPresenceOnly || awaitingForegroundStores)"))
        assertTrue(manager.contains("val alreadyRestricted = backgroundPresenceOnly && awaitingForegroundStores"))
        assertTrue(manager.contains("PresenceRetentionV3.SIGNATURE_KIND"))
        assertTrue(manager.contains("PresenceRetentionV3.SIGNATURE_FIELD"))
    }

    private fun sourceText(relativePath: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val source = File(directory, relativePath)
            if (source.exists()) return source.readText()
            directory = directory.parentFile ?: return@repeat
        }
        error("Could not locate $relativePath")
    }
}
