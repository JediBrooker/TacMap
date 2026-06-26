package com.tacmap.mgrs

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
