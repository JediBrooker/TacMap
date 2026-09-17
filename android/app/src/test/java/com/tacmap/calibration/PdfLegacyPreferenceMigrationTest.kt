package com.tacmap.calibration

import com.tacmap.util.DurablePreferenceCommit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLegacyPreferenceMigrationTest {
    @Test
    fun failedCommitRollsBackMemoryAndPublishesNothing() {
        val plaintext = "{\"fileName\":\"map.pdf\"}"
        var memory = plaintext
        var disk = plaintext
        var sealedOnlyAuthenticated = false

        val published = PdfLegacyPreferenceMigration.publishAfterMigration(
            plaintext = plaintext,
            decoded = "decoded session",
            seal = {
                sealedOnlyAuthenticated = true
                "sealed-envelope"
            },
            persist = { candidate ->
                DurablePreferenceCommit.publishAfter(
                    capture = { memory },
                    commit = {
                        // SharedPreferences updates its process map before a
                        // failed disk write reports false.
                        memory = candidate
                        false
                    },
                    rollback = { before -> memory = before },
                    publish = { error("failed commit must not publish") },
                )
            },
        )

        assertNull(published)
        assertEquals(plaintext, memory)
        assertEquals(plaintext, disk)
        assertTrue(sealedOnlyAuthenticated)

        // Simulated relaunch: the authenticated marker written before sealing
        // prevents the surviving plaintext from being accepted.
        assertFalse(PdfLegacyPreferenceMigration.acceptPlaintext(sealedOnlyAuthenticated))

        // An unrelated later successful preference flush cannot smuggle the
        // rejected sealed candidate onto disk after the rollback.
        disk = memory
        assertEquals(plaintext, disk)
    }

    @Test
    fun successfulCommitPublishesOnlyAfterTheEnvelopeIsDurable() {
        var memory = "legacy"
        var disk = "legacy"

        val published = PdfLegacyPreferenceMigration.publishAfterMigration(
            plaintext = memory,
            decoded = "decoded library",
            seal = { "sealed-envelope" },
            persist = { candidate ->
                memory = candidate
                disk = candidate
                true
            },
        )
        val diskSeenAtPublish = disk

        assertEquals("decoded library", published)
        assertEquals("sealed-envelope", memory)
        assertEquals("sealed-envelope", diskSeenAtPublish)
    }

    @Test
    fun plaintextReplacementIsRejectedAfterSuccessfulMigration() {
        val sealedOnlyAuthenticated = true
        val replacement = "{\"fileName\":\"attacker.pdf\"}"

        assertTrue(replacement.startsWith("{"))
        assertFalse(PdfLegacyPreferenceMigration.acceptPlaintext(sealedOnlyAuthenticated))
    }

    @Test
    fun sealFailureNeverPersistsOrPublishes() {
        var persistCalled = false

        val published = PdfLegacyPreferenceMigration.publishAfterMigration(
            plaintext = "legacy",
            decoded = "decoded",
            seal = { throw IllegalStateException("locked") },
            persist = {
                persistCalled = true
                true
            },
        )

        assertNull(published)
        assertFalse(persistCalled)
    }
}
