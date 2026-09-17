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
