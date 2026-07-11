package com.tacmap.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCryptoTest {

    private val aad = SyncCrypto.aad("obj-1", 7L, "waypoint")

    @Test
    fun roomIdIsStableUrlSafeAndCodeSpecific() {
        val a = SyncCrypto.roomId("alpha-bravo-charlie")
        assertEquals("same code -> same id", a, SyncCrypto.roomId("alpha-bravo-charlie"))
        assertTrue("url-safe base64", a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals("different code -> different id", a, SyncCrypto.roomId("delta-echo"))
    }

    @Test
    fun roomIdRoomKeyAndAuthTokenAreDistinct() {
        val keys = SyncCrypto.deriveRoom("unit-7")
        // three independant derivations from same master - routing id,
        // AEAD key, and writer-auth token must not coincide
        assertNotEquals(keys.roomId, keys.authToken)
        assertEquals(32, keys.roomKey.size)
        assertTrue(keys.authToken.isNotEmpty())
    }

    @Test
    fun roomKeyIs32Bytes() {
        assertEquals(32, SyncCrypto.roomKey("unit-7").size)
    }

    @Test
    fun derivationIsPinnedForCrossPlatformInterop() {
        // Byte-pinned so Android and iOS PROVABLY derive the same room from the
        // same join code (the threat model's "both platforms interoperate on the
        // same join code" claim, which was otherwise untested). The iOS
        // SyncCryptoTests pin the identical values. Reference: PBKDF2-HMAC-SHA256
        // 210k over "tacmap-sync-salt-v2", HMAC-SHA256 subkeys, base64url no-pad.
        // If you rev the KDF/salt, regenerate on ONE impl and update BOTH here.
        val keys = SyncCrypto.deriveRoom("alpha-bravo-charlie")
        assertEquals("rw6A3NDGVQLSoee4dXFKgBcEJDW5Vlo--Mvvymguc0k", keys.roomId)
        assertEquals("KMnOnROo3p8dpbSRyU38w56daHTftk6NN3F6ApgXv7c", keys.authToken)
        assertEquals(
            "bf0fc618150a534d2dceaba7f956ecc8db0e389e58f1ec87b3e62305ec15b742",
            keys.roomKey.joinToString("") { "%02x".format(it) }
        )
    }

    @Test
    fun sealOpenRoundTrips() {
        val key = SyncCrypto.roomKey("unit-7-key")
        val plaintext = """{"id":"abc","name":"OP North"}""".toByteArray(Charsets.UTF_8)
        val blob = SyncCrypto.seal(key, plaintext, aad)
        // iv(12) + ct + tag(16), always bigger than plaintext
        assertTrue(blob.size >= plaintext.size + 28)
        assertArrayEquals(plaintext, SyncCrypto.open(key, blob, aad))
    }

    @Test
    fun wrongKeyFailsToOpen() {
        val blob = SyncCrypto.seal(SyncCrypto.roomKey("code-one"), "secret".toByteArray(), aad)
        assertNull(SyncCrypto.open(SyncCrypto.roomKey("code-two"), blob, aad))
    }

    @Test
    fun wrongAadFailsToOpen() {
        // if a relay swaps this blob onto a different object id / version
        // it reconstructs a different AAD, so auth fails
        val key = SyncCrypto.roomKey("k")
        val blob = SyncCrypto.seal(key, "payload".toByteArray(), aad)
        val otherAad = SyncCrypto.aad("obj-2", 7L, "waypoint")
        assertNull(SyncCrypto.open(key, blob, otherAad))
        // Same AAD still opens.
        assertArrayEquals("payload".toByteArray(), SyncCrypto.open(key, blob, aad))
    }

    @Test
    fun tamperedBlobFailsToOpen() {
        val key = SyncCrypto.roomKey("k")
        val blob = SyncCrypto.seal(key, "payload".toByteArray(), aad)
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte()  // flip a tag bit
        assertNull(SyncCrypto.open(key, blob, aad))
    }
}
