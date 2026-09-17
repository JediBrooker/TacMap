package com.tacmap.localization

import com.tacmap.waypoints.ReinforcementStatus
import com.tacmap.waypoints.TaskColor
import com.tacmap.models.HeadingNorthReference
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizationTest {
    @Test fun retainedEditorFailuresKeepRetryAndDraftStateAcrossLanguageChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        L10n.install(context)
        val original = AppLanguage.selection
        try {
            AppLanguage.select(context, SupportedLanguage.ENGLISH)
            var attempts = 0
            val failure = com.tacmap.map.DrawingMutationUiCoordinator.attempt(
                com.tacmap.map.DrawingMutationIntent.EDIT) { attempts += 1; false }
                as com.tacmap.map.DrawingMutationUiResult.Failed
            val english = failure.message
            val validation = com.tacmap.map.MGRS_MOVE_VALIDATION_MESSAGE
            val identity = validation.id
            val plural = Messages.pointCountMessage(2)
            AppLanguage.select(context, SupportedLanguage.GERMAN)
            org.junit.Assert.assertNotEquals(english, failure.message)
            assertEquals(1, attempts)
            org.junit.Assert.assertFalse(failure.saved)
            org.junit.Assert.assertFalse(failure.shouldCloseTransientUi)
            assertEquals(identity, validation.id)
            assertEquals(validation.text, com.tacmap.map.MGRS_MOVE_VALIDATION_ERROR)
            assertEquals("2 Punkte", plural.text)
        } finally { AppLanguage.select(context, original) }
    }

    @Test fun lockedChatHistoryTracksLanguageWithoutUnlockingOrWriting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        L10n.install(context)
        val original = AppLanguage.selection
        val directory = java.io.File(context.cacheDir, "chat-localisation-${java.util.UUID.randomUUID()}")
        directory.mkdirs()
        try {
            AppLanguage.select(context, SupportedLanguage.ENGLISH)
            val store = com.tacmap.sync.TacMapChatHistoryStore.forTests(directory)
            store.lock()
            val retained = store.issue.value
            assertEquals("Unlock mission data to use TacMap Chat", retained?.text)
            AppLanguage.select(context, SupportedLanguage.GERMAN)
            assertEquals("Entsperre Einsatzdaten, um TacMap Chat zu verwenden", store.issue.value?.text)
            assertEquals(retained, store.issue.value)
            assertEquals(com.tacmap.sync.TacMapChatHistoryAvailability.LOCKED, store.availability.value)
            org.junit.Assert.assertTrue(store.messages.value.isEmpty())
            org.junit.Assert.assertTrue(directory.listFiles()!!.isEmpty())
        } finally {
            AppLanguage.select(context, original)
            directory.deleteRecursively()
        }
    }

    @Test fun retainedLiveLocationControlsRefreshWithoutChangingActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        L10n.install(context)
        val original = AppLanguage.selection
        try {
            AppLanguage.select(context, SupportedLanguage.ENGLISH)
            val control = com.tacmap.models.LiveMapLocationPermissionPolicy.controlFor(
                com.tacmap.models.LiveMapLocationState.Denied)
            val guidance = com.tacmap.models.LiveMapLocationPermissionPolicy.guidanceFor(
                com.tacmap.models.LocationAccess.ApproximateOnly)!!
            assertEquals("Open Location Settings", control.title)
            org.junit.Assert.assertTrue(guidance.message.contains("Allow Precise location"))
            AppLanguage.select(context, SupportedLanguage.GERMAN)
            assertEquals("Standorteinstellungen öffnen", control.title)
            org.junit.Assert.assertTrue(guidance.message.contains("Erlaube den genauen Standort"))
            assertEquals(com.tacmap.models.LiveMapLocationAction.OpenSettings, control.action)
            assertEquals(com.tacmap.models.TrackRecordingSettingsTarget.AppPermissions, guidance.settingsTarget)
            assertEquals(SupportedLanguage.GERMAN, AppLanguage.selections.value)
        } finally { AppLanguage.select(context, original) }
    }

    @Test fun builtinLayerDisplayPreservesSerializedDataAndCustomNames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        L10n.install(context)
        val original = AppLanguage.selection
        try {
            AppLanguage.select(context, SupportedLanguage.ENGLISH)
            val layer = com.tacmap.drawings.DrawingDocument.defaultLayer()
            val custom = layer.copy(id = "custom")
            val edited = layer.copy(name = "My team")
            val encoder = kotlinx.serialization.json.Json { encodeDefaults = true }
            val before = encoder.encodeToString(com.tacmap.drawings.DrawingLayer.serializer(), layer)
            AppLanguage.select(context, SupportedLanguage.GERMAN)
            assertEquals("Eigene Kräfte", layer.displayName)
            assertEquals("Friendly", layer.name)
            assertEquals("Friendly", custom.displayName)
            assertEquals("My team", edited.displayName)
            assertEquals(before, encoder.encodeToString(com.tacmap.drawings.DrawingLayer.serializer(), layer))
            val germanSeed = com.tacmap.drawings.DrawingDocument.defaultLayer()
            assertEquals("Eigene Kräfte", germanSeed.name)
            AppLanguage.select(context, SupportedLanguage.ENGLISH)
            assertEquals("Friendly", germanSeed.displayName)
            assertEquals("Eigene Kräfte", germanSeed.name)
            for (count in listOf(0, 1, 2, 10000)) {
                assertEquals("Send to $count " + if (count == 1) "unit" else "units", Messages.sendUnitsCount(count))
            }
            AppLanguage.select(context, SupportedLanguage.GERMAN)
            assertEquals("An 2 Einheiten senden", Messages.sendUnitsCount(2))
            assertEquals("2 Zeichnungen", Messages.drawingCount(2))
        } finally { AppLanguage.select(context, original) }
    }

    @Test fun retainedBillingMessagesChangeLanguageWithoutChangingPurchaseState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        L10n.install(context)
        val original = AppLanguage.selection
        try {
            AppLanguage.select(context, SupportedLanguage.ENGLISH)
            val loading = com.tacmap.billing.BillingUiReducer.reduce(
                com.tacmap.billing.BillingUiState(priceText = "€9.99"),
                com.tacmap.billing.BillingUiEvent.Connecting)
            val issue = com.tacmap.billing.BillingStoreIssues.purchaseAcknowledgement()
            val reason = Messages.billingTheTacmapUnlockProductIsNotAvailableForThisMessage()
            val nested = Messages.billingReloadTheCurrentGooglePlayOfferBeforeTryingAgainMessage(reason.text)
                .withArgument(0, reason)
            assertEquals("Connecting to Google Play…", loading.message)
            val english = nested.text
            AppLanguage.select(context, SupportedLanguage.GERMAN)
            assertEquals("Verbindung zu Google Play wird hergestellt …", loading.message)
            assertEquals("Google Play prüfen", issue.title)
            org.junit.Assert.assertFalse(nested.text == english)
            org.junit.Assert.assertTrue(nested.text.startsWith(reason.text))
            assertEquals(com.tacmap.billing.BillingPhase.Connecting, loading.phase)
            org.junit.Assert.assertFalse(loading.purchaseEnabled)
            assertEquals(com.tacmap.billing.BillingStoreIssueKind.PurchaseAcknowledgement, issue.kind)
        } finally { AppLanguage.select(context, original) }
    }

    @Test fun retainedSyncSecurityWarningPreservesGenerationAndPriority() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        L10n.install(context)
        val original = AppLanguage.selection
        try {
            AppLanguage.select(context, SupportedLanguage.ENGLISH)
            val lifecycle = com.tacmap.sync.SyncIssueLifecycle()
            val warned = lifecycle.beginConnection()
            val message = Messages.syncSyncRollbackWarningTheRelaySnapshotIsOlderThanMessage()
            lifecycle.report(message, com.tacmap.sync.SyncIssueKind.SECURITY, warned)
            val english = lifecycle.issue?.message
            AppLanguage.select(context, SupportedLanguage.GERMAN)
            org.junit.Assert.assertFalse(lifecycle.issue?.message == english)
            org.junit.Assert.assertSame(message, lifecycle.issue?.pendingMessage)
            assertEquals(warned, lifecycle.issue?.generation)
            assertEquals(com.tacmap.sync.SyncIssueKind.SECURITY, lifecycle.issue?.kind)
            org.junit.Assert.assertNotNull(lifecycle.connectionSucceeded(warned, true))
            val next = lifecycle.beginConnection()
            lifecycle.report(Messages.syncUnitSyncDisconnectedCheckTheRelayOrNetworkReconnectingMessage(),
                com.tacmap.sync.SyncIssueKind.CONNECTION, next)
            org.junit.Assert.assertSame(message, lifecycle.issue?.pendingMessage)
            org.junit.Assert.assertNull(lifecycle.connectionSucceeded(next, true))
        } finally { AppLanguage.select(context, original) }
    }

    @Test fun retainedRecordingStatesRefreshWithoutChangingTransitions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        L10n.install(context)
        val original = AppLanguage.selection
        val waiting = com.tacmap.models.TrackRecordingReducer.reduce(
            com.tacmap.models.TrackRecordingUiState(),
            com.tacmap.models.TrackRecordingEvent.StartRequested(com.tacmap.models.LocationAccess.Denied, true),
        )
        val detail = "100% {1} / recording.ndjson"
        val error = Messages.recordingActivationFailedMessage(detail)
        val stopped = com.tacmap.models.TrackRecordingReducer.reduce(
            com.tacmap.models.TrackRecordingUiState(phase = com.tacmap.models.TrackRecordingPhase.Recording),
            com.tacmap.models.TrackRecordingEvent.Interrupted(error),
        )
        try {
            org.junit.Assert.assertTrue(AppLanguage.select(context, SupportedLanguage.ENGLISH))
            assertEquals("Precise location permission is required to record a GPS track.", waiting.message)
            assertEquals("Could not activate background track recording: " + detail, stopped.message)
            org.junit.Assert.assertTrue(AppLanguage.select(context, SupportedLanguage.GERMAN))
            assertEquals("Für die GPS-Trackaufzeichnung ist die Berechtigung für den genauen Standort nötig.", waiting.message)
            assertEquals("Hintergrund-Trackaufzeichnung konnte nicht aktiviert werden: " + detail, stopped.message)
            assertEquals(com.tacmap.models.TrackRecordingPhase.AwaitingPermission, waiting.phase)
            assertEquals(com.tacmap.models.TrackRecordingSettingsTarget.AppPermissions, waiting.settingsTarget)
            assertEquals(com.tacmap.models.TrackRecordingPhase.Interrupted, stopped.phase)
            org.junit.Assert.assertSame(error, stopped.pendingMessage)
            org.junit.Assert.assertFalse(stopped.isAuthorizedSession)
        } finally {
            AppLanguage.select(context, original)
        }
    }

    @Test fun languageSelectionPersistsAndRefreshesTextAndPlurals() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        L10n.install(context)
        val pending = Messages.trackReencryptFailedMessage("100% {1} / saved.gpx")
        val original = AppLanguage.selection
        try {
            org.junit.Assert.assertTrue(AppLanguage.select(context, SupportedLanguage.ENGLISH))
            assertEquals("Save", L10n.text("Save"))
            assertEquals("Language", Messages.settingsLanguageTitle())
            assertEquals("en", DisplayFormat.currentLocale.language)
            assertEquals("Could not encrypt the recovered track: 100% {1} / saved.gpx", pending.text)
            assertEquals("Import failed: 100% {1}", Messages.importFailed("100% {1}"))
            assertEquals("None", ReinforcementStatus.NONE.displayName)
            assertEquals("Black", TaskColor.BLACK.displayName)
            assertEquals("true north", HeadingNorthReference.TRUE_NORTH.accessibilityLabel)
            org.junit.Assert.assertTrue(AppLanguage.select(context, SupportedLanguage.GERMAN))
            assertEquals("Speichern", L10n.text("Save"))
            assertEquals("Sprache", Messages.settingsLanguageTitle())
            assertEquals("de", DisplayFormat.currentLocale.language)
            assertEquals("Der wiederhergestellte Track konnte nicht verschlüsselt werden: 100% {1} / saved.gpx", pending.text)
            assertEquals("Import fehlgeschlagen: 100% {1}", Messages.importFailed("100% {1}"))
            assertEquals("2 Punkte", Messages.pointCount(2))
            assertEquals("Keine", ReinforcementStatus.NONE.displayName)
            assertEquals("Verstärkt (+)", ReinforcementStatus.REINFORCED.displayName)
            assertEquals("Schwarz", TaskColor.BLACK.displayName)
            assertEquals("geografisch Nord", HeadingNorthReference.TRUE_NORTH.accessibilityLabel)
            assertEquals("1 Punkt", L10n.quantity("point", 1))
            assertEquals("2 Punkte", L10n.quantity("point", 2))
            AppLanguage.install(context)
            assertEquals(SupportedLanguage.GERMAN, AppLanguage.selection)
            org.junit.Assert.assertTrue(AppLanguage.select(context, SupportedLanguage.ENGLISH))
            assertEquals("Save", L10n.text("Save"))
            assertEquals("Language", Messages.settingsLanguageTitle())
            assertEquals("en", DisplayFormat.currentLocale.language)
            assertEquals("Could not encrypt the recovered track: 100% {1} / saved.gpx", pending.text)
            assertEquals("Import failed: 100% {1}", Messages.importFailed("100% {1}"))
            assertEquals("None", ReinforcementStatus.NONE.displayName)
            assertEquals("Black", TaskColor.BLACK.displayName)
            assertEquals("true north", HeadingNorthReference.TRUE_NORTH.accessibilityLabel)
        } finally {
            AppLanguage.select(context, original)
        }
    }

    private fun resources(language: String): Resources {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(language))
        return context.createConfigurationContext(configuration).resources
    }

    @Test fun germanAndRegionalGermanUseGermanResources() {
        for (language in listOf("de", "de-DE", "de-AT", "de-CH")) {
            val resources = resources(language)
            assertEquals("Speichern", resources.getString(localizedStringIds.getValue("Save")))
            assertEquals("Zug", resources.getString(localizedStringIds.getValue("Platoon")))
            assertEquals("Eigene Kräfte", resources.getString(localizedStringIds.getValue("Friendly")))
        }
    }

    @Test fun unsupportedLanguageFallsBackToEnglish() {
        assertEquals("Save", resources("fr").getString(localizedStringIds.getValue("Save")))
    }

    @Test fun dynamicTextPreservesUserContent() {
        val detail = "100% – Karte {1} \"Alpha\""
        assertEquals("Import fehlgeschlagen: $detail", resources("de").getString(
            localizedStringIds.getValue("Import failed: %1\$s"), detail))
    }

    @Test fun countsUseGermanSingularAndPluralIncludingZero() {
        val resources = resources("de")
        val id = localizedPluralIds.getValue("point")
        assertEquals("0 Punkte", resources.getQuantityString(id, 0, 0))
        assertEquals("1 Punkt", resources.getQuantityString(id, 1, 1))
        assertEquals("2 Punkte", resources.getQuantityString(id, 2, 2))
        val days = localizedPluralIds.getValue("trial_remaining")
        assertEquals("Kostenloser Test – noch 1 Tag", resources.getQuantityString(days, 1, 1))
        assertEquals("Kostenloser Test – noch 3 Tage", resources.getQuantityString(days, 3, 3))
    }
}

/** Release experiment, run explicitly on an isolated emulator. Native locale
 * migration stays gated while editor drafts use non-saveable remember state. */
@RunWith(AndroidJUnit4::class)
class NativeLocalePrototypeTest {
    @Test fun nativeLocaleChangeRecreatesActivityWhileInAppChoiceDoesNot() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 33)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(android.app.LocaleManager::class.java)
        val originalLocales = manager.applicationLocales
        val originalChoice = AppLanguage.selection
        val scenario = androidx.test.core.app.ActivityScenario.launch(com.tacmap.app.MainActivity::class.java)
        try {
            lateinit var original: com.tacmap.app.MainActivity
            scenario.onActivity { original = it; AppLanguage.select(it, SupportedLanguage.GERMAN) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { org.junit.Assert.assertSame(original, it) }
            val target = if (originalLocales.toLanguageTags() == "de") "en" else "de"
            instrumentation.runOnMainSync { manager.applicationLocales = LocaleList.forLanguageTags(target) }
            val deadline = android.os.SystemClock.uptimeMillis() + 15000
            var recreated = false
            while (!recreated && android.os.SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                scenario.onActivity { recreated = it !== original }
                if (!recreated) android.os.SystemClock.sleep(100)
            }
            org.junit.Assert.assertTrue("Native API must exercise the Activity recreation risk", recreated)
            assertEquals(SupportedLanguage.GERMAN, AppLanguage.selection)
        } finally {
            instrumentation.runOnMainSync { manager.applicationLocales = originalLocales }
            AppLanguage.select(context, originalChoice)
            scenario.close()
        }
    }
}

/** Explicit release check uses a temporary recorder, never the user's track. */
@RunWith(AndroidJUnit4::class)
class RecordingNotificationLocaleTest {
    @Test fun runningServiceChangesNotificationWithoutReplacingRecordingSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as com.tacmap.app.TacticalApp
        org.junit.Assume.assumeTrue(!app.trackRecorder.isRecording.value)
        val original = app.trackRecorder
        val originalLanguage = AppLanguage.selection
        val temporary = java.io.File(context.cacheDir, "locale-notification-${java.util.UUID.randomUUID()}.ndjson")
        val recorder = com.tacmap.models.TrackRecorder(temporary, recordingKeyProvider = { ByteArray(32) { 42 } })
        // Test-only substitution keeps production constructors and durable data untouched.
        val field = com.tacmap.app.TacticalApp::class.java.getDeclaredField("trackRecorder").apply { isAccessible = true }
        field.set(app, recorder)
        val scenario = androidx.test.core.app.ActivityScenario.launch(com.tacmap.app.MainActivity::class.java)
        val notifications = context.getSystemService(android.app.NotificationManager::class.java)
        fun awaitTitle(expected: String) {
            val deadline = android.os.SystemClock.uptimeMillis() + 10000
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                if (notifications.activeNotifications.any { it.id == 4201 && it.notification.extras.getString(android.app.Notification.EXTRA_TITLE) == expected }) return
                android.os.SystemClock.sleep(100)
            }
            org.junit.Assert.fail("Recording notification did not become: $expected")
        }
        try {
            scenario.onActivity {
                AppLanguage.select(it, SupportedLanguage.ENGLISH)
                org.junit.Assert.assertTrue(recorder.requestStart(com.tacmap.models.LocationAccess.Precise, true))
                org.junit.Assert.assertTrue(recorder.prepareStart())
                com.tacmap.models.TrackRecordingService.start(it)
            }
            awaitTitle("Recording patrol track")
            val activationDeadline = android.os.SystemClock.uptimeMillis() + 10000
            while (!recorder.isRecording.value && android.os.SystemClock.uptimeMillis() < activationDeadline) {
                android.os.SystemClock.sleep(100)
            }
            val state = recorder.uiState.value
            val generationField = com.tacmap.models.TrackRecorder::class.java.getDeclaredField("currentSessionGeneration").apply { isAccessible = true }
            val generation = generationField.get(recorder)
            org.junit.Assert.assertTrue(recorder.isRecording.value)
            androidx.test.uiautomator.UiDevice.getInstance(instrumentation).pressHome()
            instrumentation.runOnMainSync { AppLanguage.select(context, SupportedLanguage.GERMAN) }
            awaitTitle(L10n.text("Recording patrol track"))
            assertEquals(state.phase, recorder.uiState.value.phase)
            assertEquals(generation, generationField.get(recorder))
            org.junit.Assert.assertTrue(recorder.isRecording.value)
            org.junit.Assert.assertSame(recorder, app.trackRecorder)
        } finally {
            instrumentation.runOnMainSync {
                recorder.stop()
                com.tacmap.models.TrackRecordingService.stop(context)
                AppLanguage.select(context, originalLanguage)
            }
            instrumentation.waitForIdleSync()
            scenario.close()
            field.set(app, original)
            temporary.delete()
        }
    }
}
