package com.tacmap.mgrs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Covers the formatting + crash-safe parsing the app layers on top of NGA's
 * `mgrs` library. The underlying conversion is NGA's responsibility; the
 * display spacing and null-on-garbage contract are ours.
 */
class MgrsFormatterTest {

    @Test
    fun formatProducesSpacedTriad() {
        val s = MgrsFormatter.format(37.7749, -122.4194)
        assertTrue("unexpected MGRS: $s", Regex("""^\d{1,2}[A-Z]{3} \d{5} \d{5}$""").matches(s))
    }

    @Test
    fun unspacedFormatIsCompact() {
        val s = MgrsFormatter.format(37.7749, -122.4194, spaced = false)
        assertTrue("unexpected MGRS: $s", Regex("""^\d{1,2}[A-Z]{3}\d{10}$""").matches(s))
    }

    @Test
    fun parseFormatRoundTrips() {
        val lat = 37.7749
        val lng = -122.4194
        val back = MgrsFormatter.parse(MgrsFormatter.format(lat, lng))
        assertTrue("parse returned null", back != null)
        val (rlat, rlng) = back!!
        assertTrue(abs(rlat - lat) < 1e-3)
        assertTrue(abs(rlng - lng) < 1e-3)
    }

    @Test
    fun parseRejectsGarbageWithoutCrashing() {
        assertNull(MgrsFormatter.parse("hello"))
        assertNull(MgrsFormatter.parse(""))
        assertNull(MgrsFormatter.parse("H"))
    }

    @Test
    fun parseRejectsPartialAndInvalidGrids() {
        // GZD-only / partial: must not resolve to a far grid-zone corner (parity
        // with iOS, which rejects these).
        assertNull(MgrsFormatter.parse("56"))         // zone only
        assertNull(MgrsFormatter.parse("56H"))        // zone + band, no square
        assertNull(MgrsFormatter.parse("10S"))        // looks like GZD, not a full ref
        // Structurally invalid: bad band / zone.
        assertNull(MgrsFormatter.parse("56ALH1234"))  // band A is UPS, not a UTM band
        assertNull(MgrsFormatter.parse("00HLH1234"))  // zone 00
        assertNull(MgrsFormatter.parse("61HLH1234"))  // zone 61
    }

    @Test
    fun looksLikeMgrsAcceptsValidRejectsInvalid() {
        assertTrue(MgrsFormatter.looksLikeMgrs("56HLH1322537516"))
        assertTrue(MgrsFormatter.looksLikeMgrs("4QFJ1234"))
        assertTrue(MgrsFormatter.looksLikeMgrs("56HLH"))     // 100km square (coarse but valid)
        assertFalse(MgrsFormatter.looksLikeMgrs("56ILH1234")) // band I not permitted
        assertFalse(MgrsFormatter.looksLikeMgrs("560HLH"))    // 3-digit zone
        assertFalse(MgrsFormatter.looksLikeMgrs("HELLO"))
    }

    @Test
    fun polarLatitudesShowOutOfRangeNotWrongGrid() {
        // Beyond the 84N/80S UTM limit the NGA library clamps to band X/C (a grid
        // ~110 km off), so we show an explicit out-of-range marker instead.
        assertTrue(MgrsFormatter.format(85.0, 10.0).contains("84"))
        assertTrue(MgrsFormatter.format(-85.0, 10.0).contains("80"))
        // In-range still produces a normal spaced grid.
        assertTrue(Regex("""^\d{1,2}[A-Z]{3} \d{5} \d{5}$""").matches(MgrsFormatter.format(37.7749, -122.4194)))
    }

    @Test
    fun formatUtmNorthernHemisphere() {
        // San Francisco → UTM zone 10 N.
        val s = MgrsFormatter.formatUtm(37.7749, -122.4194)
        assertTrue("unexpected UTM: $s", Regex("""^\d{2}[NS] \d+mE \d+mN$""").matches(s))
        assertTrue("expected zone 10N: $s", s.startsWith("10N "))
    }

    @Test
    fun formatUtmSouthernHemisphere() {
        // Sydney → UTM zone 56 S.
        val s = MgrsFormatter.formatUtm(-33.8688, 151.2093)
        assertTrue("unexpected UTM: $s", Regex("""^\d{2}S \d+mE \d+mN$""").matches(s))
        assertTrue("expected zone 56S: $s", s.startsWith("56S "))
    }
}
