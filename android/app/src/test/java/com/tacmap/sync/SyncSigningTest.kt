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
    fun presenceMessageIsPinnedForCrossPlatform() {
        // The exact bytes a presence signature covers. iOS SyncSigningTests pins
        // the identical hex - fixed %.6f + U+001F join means an Android-signed
        // presence verifies on iOS and vice-versa.
        val msg = SyncSigning.presenceMessage(
            "dev-1", 1_700_000_000_000L, 37.8065, -122.4103, 90.0, 1.5,
            "ALPHA-1", "FRIEND", "TEAM", "INFANTRY", true)
        assertEquals(
            "6465762d311f313730303030303030303030301f33372e3830363530301f2d3132322e343130" +
                "3330301f39302e3030303030301f312e3530303030301f414c5048412d311f465249454e44" +
                "1f5445414d1f494e46414e5452591f31",
            msg.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun objectMessageIsPinnedForCrossPlatform() {
        // The exact bytes an object-write signature covers, put and delete.
        // iOS SyncSigningTests pins the identical hex, so an Android-signed
        // object write verifies on iOS and vice-versa.
        val put = SyncSigning.objectMessage("obj-1", 7L, "waypoint", "dev-1", "GEO")
        assertEquals("6f626a2d311f371f776179706f696e741f6465762d311f47454f", put.joinToString("") { "%02x".format(it) })
        val del = SyncSigning.objectMessage("obj-1", 7L, "del", "dev-1", "")
        assertEquals("6f626a2d311f371f64656c1f6465762d311f", del.joinToString("") { "%02x".format(it) })
        // A signature over the put must not verify against the delete message
        // (kind + content differ), so a relay can't swap a write for a delete.
        val s2 = SyncSigning.generateSeed()
        val pub = SyncSigning.publicKey(s2)
        val putSig = SyncSigning.sign(s2, put)
        assertTrue(SyncSigning.verify(pub, put, putSig))
        assertFalse(SyncSigning.verify(pub, del, putSig))
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
