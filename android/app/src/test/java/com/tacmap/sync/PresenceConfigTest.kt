package com.tacmap.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceConfigTest {
    @Test
    fun localRoomNameIsBoundedByUnicodeCodePoint() {
        val bounded = boundRoomName("😀".repeat(MAX_ROOM_NAME_CODE_POINTS + 5))

        assertEquals(MAX_ROOM_NAME_CODE_POINTS, bounded.codePointCount(0, bounded.length))
        assertEquals(
            "Command Room",
            PresenceConfig(roomNamesById = mapOf("room-id" to "Command Room"))
                .roomNamesById.getValue("room-id"),
        )
    }

    @Test
    fun namesRemainAssociatedWithTheirDerivedRoomIdsAndClearIndependently() {
        val alpha = updatedRoomNamesById(emptyMap(), "derived-alpha", "Alpha Room")
        val both = updatedRoomNamesById(alpha, "derived-bravo", "Bravo Room")
        val clearedBravo = updatedRoomNamesById(both, "derived-bravo", "  ")

        assertEquals("Alpha Room", both.getValue("derived-alpha"))
        assertEquals("Bravo Room", both.getValue("derived-bravo"))
        assertEquals(mapOf("derived-alpha" to "Alpha Room"), clearedBravo)
    }

    @Test
    fun invalidSealedRecordNeverFallsBackToLegacyLocationConsent() {
        var legacyRead = false
        val decision = resolvePresenceConfigLoad(
            sealedStored = true,
            readSealed = { null },
            readLegacy = {
                legacyRead = true
                PresenceConfig(shareLocation = true)
            },
        )

        assertEquals(PresenceConfigLoadDecision.RejectInvalidSealed, decision)
        assertFalse(legacyRead)
    }

    @Test
    fun absentSealedRecordMigratesLegacyAndValidSealedRecordWins() {
        val legacy = PresenceConfig(callsign = "11A", shareLocation = true)
        val migration = resolvePresenceConfigLoad(false, { null }, { legacy })
        assertEquals(PresenceConfigLoadDecision.MigrateLegacy(legacy), migration)

        var legacyRead = false
        val sealed = PresenceConfig(callsign = "0A", shareLocation = false)
        val loaded = resolvePresenceConfigLoad(
            sealedStored = true,
            readSealed = { sealed },
            readLegacy = {
                legacyRead = true
                legacy
            },
        )
        assertEquals(PresenceConfigLoadDecision.UseSealed(sealed), loaded)
        assertFalse(legacyRead)
        assertTrue((loaded as PresenceConfigLoadDecision.UseSealed).config.callsign == "0A")
    }
}
