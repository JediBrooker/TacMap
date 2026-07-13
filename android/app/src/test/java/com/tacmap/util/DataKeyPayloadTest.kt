package com.tacmap.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataKeyPayloadTest {
    private val dek = ByteArray(32) { it.toByte() }

    @Test fun legacyRawPayloadGetsExactlyTheMigrationPath() {
        val decoded = DataKeyPayload.decode(dek)!!
        assertArrayEquals(dek, decoded.dek)
        assertFalse(decoded.sentinelRequired)
    }

    @Test fun deletingSentinelAndMutableRequiredFlagCannotDowngradeV2Payload() {
        // The requirement bit comes from authenticated wrapped plaintext, not
        // SharedPreferences. Simulating deletion of every mutable marker does
        // not alter this decoded decision.
        val decoded = DataKeyPayload.decode(DataKeyPayload.encodeV2(dek))!!
        assertArrayEquals(dek, decoded.dek)
        assertTrue(decoded.sentinelRequired)
    }

    @Test fun malformedVersionedPayloadIsRejected() {
        val encoded = DataKeyPayload.encodeV2(dek)
        encoded[0] = (encoded[0].toInt() xor 1).toByte()
        assertNull(DataKeyPayload.decode(encoded))
    }
}
