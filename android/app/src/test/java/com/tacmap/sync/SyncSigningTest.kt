package com.tacmap.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSigningTest {

    private fun hex(s: String) = ByteArray(s.length / 2) {
        ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte()
    }

    // RFC 8032 Ed25519 TEST 1 (empty message). Pinning the standard vector proves
    // this is real Ed25519, hence byte-identical to iOS CryptoKit - the iOS
    // SyncSigningTests pin the same base64url values.
    private val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    private val pubB64 = "11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo"
    private val sigB64 = "5VZDAMNgrHKQhuLMgG6CioSHfx645dl02HPgZSJJAVVfuIIVkKM7rMYeOXAc-bRr0lv18FlbviRlUUFDjnoQCw"

    @Test
    fun matchesRfc8032Vector() {
        assertEquals(pubB64, SyncSigning.publicKey(seed))
        assertEquals(sigB64, SyncSigning.sign(seed, ByteArray(0)))
        assertTrue(SyncSigning.verify(pubB64, ByteArray(0), sigB64))
    }

    @Test
    fun rejectsTamperedMessageWrongKeyAndGarbage() {
        val seed2 = SyncSigning.generateSeed()
        val msg = "grid 1234 5678".toByteArray()
        val sig = SyncSigning.sign(seed2, msg)
        val pub = SyncSigning.publicKey(seed2)
        assertTrue(SyncSigning.verify(pub, msg, sig))
        assertFalse("tampered message", SyncSigning.verify(pub, "grid 1234 5679".toByteArray(), sig))
        assertFalse("wrong key = impersonation attempt", SyncSigning.verify(pubB64, msg, sig))
        assertFalse("garbage sig never throws", SyncSigning.verify(pub, msg, "not-base64!!"))
    }
}
