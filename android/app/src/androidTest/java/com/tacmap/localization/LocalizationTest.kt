package com.tacmap.localization

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
