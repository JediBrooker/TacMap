package com.tacmap.sync

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceRetentionV3Test {
    @Test
    fun canonicalAdvertisementDecodesAndPartialEnvelopeFailsClosed() {
        val payload = PresenceRetentionV3.encodePayload(65 * 60)!!
        val envelope = JSONObject()
            .put(PresenceRetentionV3.VERSION_FIELD, PresenceRetentionV3.ENVELOPE_VERSION)
            .put(PresenceRetentionV3.PAYLOAD_FIELD, SyncCrypto.encodeBase64(payload))
            .put(PresenceRetentionV3.SIGNATURE_FIELD, "signature")

        val decoded = PresenceRetentionV3.decode(envelope)
        assertTrue(decoded is PresenceRetentionV3.DecodeResult.Valid)
        val advertisement = (decoded as PresenceRetentionV3.DecodeResult.Valid).advertisement
        assertEquals(65 * 60, advertisement.seconds)
        assertArrayEquals(payload, advertisement.signedPayload)
        assertEquals("signature", advertisement.signature)

        assertNull(PresenceRetentionV3.encodePayload(65 * 60 + 1))
        envelope.remove(PresenceRetentionV3.SIGNATURE_FIELD)
        assertTrue(PresenceRetentionV3.decode(envelope) is PresenceRetentionV3.DecodeResult.Invalid)
    }

    @Test
    fun absentAdvertisementKeepsLegacyBehavior() {
        assertTrue(PresenceRetentionV3.decode(JSONObject()) is PresenceRetentionV3.DecodeResult.Absent)
    }
}
