package com.tacmap.localization

import org.junit.Assert.assertEquals
import org.junit.Test

class GeneratedMessagesTest {
    @Test fun pendingTrackErrorPreservesDiagnosticDetails() {
        val detail = "100% {1} / saved.gpx"
        val pending = Messages.trackReencryptFailedMessage(detail)
        assertEquals("Could not encrypt the recovered track: " + detail, pending.text)
        assertEquals(listOf(detail), pending.arguments)
        assertEquals(detail, LocalizedMessage.literal(detail).text)
    }

    @Test fun headlessEnglishFallbackPreservesUserTextAndPluralCounts() {
        assertEquals("Import failed: 100% {1}", Messages.importFailed("100% {1}"))
        assertEquals("1 point", Messages.pointCount(1))
        assertEquals("2 points", Messages.pointCount(2))
        assertEquals("Language", Messages.settingsLanguageTitle())
    }
}
