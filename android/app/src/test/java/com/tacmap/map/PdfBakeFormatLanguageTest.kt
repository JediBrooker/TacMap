package com.tacmap.map

import com.tacmap.localization.DisplayFormat
import com.tacmap.localization.L10n
import com.tacmap.localization.SupportedLanguage
import com.tacmap.map.render.pdf.PdfBakeFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * PAR-R2-1: the bake numbers follow the app UI language (en or de), never the device region.
 * de-CH used to give 1'646 and fr-FR 1 646 / 1,5 MB off DecimalFormatSymbols
 */
class PdfBakeFormatLanguageTest {
    private val saved = Locale.getDefault()

    @After
    fun restore() = Locale.setDefault(saved)

    @Test
    fun regionNeverPicksTheSeparators() {
        assertEquals("1.646", PdfBakeFormat.tiles(1646, Locale.forLanguageTag("de-CH")))
        assertEquals("1,5 MB", PdfBakeFormat.size(1_500_000, Locale.forLanguageTag("de-CH")))
        assertEquals("1,646", PdfBakeFormat.tiles(1646, Locale.forLanguageTag("fr-FR")))
        assertEquals("1.5 MB", PdfBakeFormat.size(1_500_000, Locale.forLanguageTag("fr-FR")))
        assertEquals("1,234,567", PdfBakeFormat.tiles(1_234_567, Locale.forLanguageTag("en-IN")))
    }

    @Test
    fun uiLanguageIsTheChoiceOrWhatTheCopyCameOutIn() {
        val deCh = Locale.forLanguageTag("de-CH")
        val frFr = Locale.forLanguageTag("fr-FR")
        // system: a de-CH device reads German copy, fr-FR falls back to English
        assertEquals("de", DisplayFormat.uiLanguage(SupportedLanguage.SYSTEM) { L10n.systemUiLanguage(deCh) })
        assertEquals("en", DisplayFormat.uiLanguage(SupportedLanguage.SYSTEM) { L10n.systemUiLanguage(frFr) })
        // an explicit app language wins over the device
        assertEquals("de", DisplayFormat.uiLanguage(SupportedLanguage.GERMAN) { L10n.systemUiLanguage(frFr) })
        assertEquals("en", DisplayFormat.uiLanguage(SupportedLanguage.ENGLISH) { L10n.systemUiLanguage(deCh) })
    }

    @Test
    fun theUiHelpersWithANonEnDeDeviceLocale() {
        // what the Layers row, the option list and the noSpace text actually call
        Locale.setDefault(Locale.forLanguageTag("fr-FR"))
        assertEquals("1,646", pdfBakeTiles(1646))
        assertEquals("1.5 MB", pdfBakeSize(1_500_000))
        assertEquals("1.2 GB", pdfBakeSize(1_234_567_890))
        Locale.setDefault(Locale.forLanguageTag("de-CH"))
        assertEquals("1.646", pdfBakeTiles(1646))
        assertEquals("1,5 MB", pdfBakeSize(1_500_000))
        assertEquals("1,2 GB", pdfBakeSize(1_234_567_890))
    }
}
