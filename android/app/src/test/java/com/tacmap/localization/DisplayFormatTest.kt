package com.tacmap.localization

import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayFormatTest {
    @Test fun selectedLanguagePreservesDeviceRegion() {
        val mixed = DisplayFormat.locale("de", Locale.forLanguageTag("en-AU"))
        assertEquals("de", mixed.language)
        assertEquals("AU", mixed.country)
        val device = Locale.forLanguageTag("de-CH")
        assertEquals(device, DisplayFormat.locale(null, device))
    }

    @Test fun numbersAndMetricThresholds() {
        val de = Locale.GERMANY
        assertEquals("-12,5", DisplayFormat.number(-12.5, 1, de))
        assertEquals("1234.50", DisplayFormat.number(1234.5, 2, Locale.US))
        assertEquals("12.5", DisplayFormat.number(12.5, 1, Locale.forLanguageTag("de-CH")))
        assertEquals("999 m", DisplayFormat.distance(999.0, de))
        assertEquals("1,25 km", DisplayFormat.distance(1250.0, de))
        assertEquals("100 km", DisplayFormat.distance(100_000.0, de))
        assertEquals("9999 m²", DisplayFormat.area(9999.0, de))
        assertEquals("1,25 ha", DisplayFormat.area(12_500.0, de))
        assertEquals("1.25 km²", DisplayFormat.area(1_250_000.0, Locale.US))
        assertEquals("—", DisplayFormat.number(Double.NaN, 1, de))
    }

    @Test fun germanDeviceLocaleDoesNotChangeSignedCoordinates() {
        val original = Locale.getDefault()
        fun bytes() = com.tacmap.sync.SyncSigning.presenceMessage(
            "dev-1", 1_700_000_000_000L, 37.8065, -122.4103, 90.0, 1.5,
            "ALPHA-1", "FRIEND", "TEAM", "INFANTRY", true)
        try {
            Locale.setDefault(Locale.US)
            val before = bytes()
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1,25", DisplayFormat.number(1.25, 2))
            org.junit.Assert.assertArrayEquals(before, bytes())
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun timeUsesExplicitZoneWithoutChangingInstant() {
        val date = Date(0)
        assertEquals("00:00", DisplayFormat.time(date, Locale.GERMANY, TimeZone.getTimeZone("UTC")))
        assertEquals("01:00", DisplayFormat.time(date, Locale.GERMANY, TimeZone.getTimeZone("GMT+01:00")))
        assertEquals(0L, date.time)
    }
}
