package com.tacmap.localization

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecimalInputTest {
    @Test fun decimalGrammarAndRegionalSeparators() {
        assertEquals(-123.5, DecimalInput.parse(" -123,5 ", Locale.GERMANY))
        assertEquals(123.5, DecimalInput.parse("123.5", Locale.GERMANY))
        assertEquals(50.0, DecimalInput.parse("+0,5e2", Locale.GERMANY))
        assertEquals(0.5, DecimalInput.parse(".5", Locale.US))
        assertEquals(1.234, DecimalInput.parse("1,234", Locale.GERMANY))
        assertNull(DecimalInput.parse("1,234", Locale.US))
        assertNull(DecimalInput.parse("1,5", Locale.forLanguageTag("de-CH")))
    }

    @Test fun rejectsGroupingCoordinatesPartialAndNonFiniteValues() {
        for (text in listOf("", "1.234,5", "1,234.5", "1 234", "1’234", "12 m", "12,5,6", "1\n2", "NaN", "Infinity", "1e999", "0x10", "12;34", "--1")) {
            assertNull(text, DecimalInput.parse(text, Locale.GERMANY))
            assertNull(text, DecimalInput.parse(text, Locale.US))
        }
    }

    @Test fun canonicalStoredValuesRoundTripWithoutRounding() {
        for (value in listOf(0.0, -0.25, 123.4567890123, 1e-12, 1e20)) {
            assertEquals(value, DecimalInput.parse(value.toString(), Locale.GERMANY))
        }
    }
}
