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
