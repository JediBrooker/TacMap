package com.tacmap.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCryptoTest {

    @Test
    fun roomIdIsStableUrlSafeAndCodeSpecific() {
        val a = SyncCrypto.roomId("alpha-bravo-charlie")
        assertEquals("same code -> same id", a, SyncCrypto.roomId("alpha-bravo-charlie"))
        assertTrue("url-safe base64", a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals("different code -> different id", a, SyncCrypto.roomId("delta-echo"))
    }

    @Test
    fun roomKeyIs32Bytes() {
        assertEquals(32, SyncCrypto.roomKey("unit-7").size)
    }

    @Test
    fun sealOpenRoundTrips() {
        val key = SyncCrypto.roomKey("unit-7-key")
        val plaintext = """{"id":"abc","name":"OP North"}""".toByteArray(Charsets.UTF_8)
        val blob = SyncCrypto.seal(key, plaintext)
        // iv(12) + ct + tag(16) — strictly larger than the plaintext.
        assertTrue(blob.size >= plaintext.size + 28)
        assertArrayEquals(plaintext, SyncCrypto.open(key, blob))
    }

    @Test
    fun wrongKeyFailsToOpen() {
        val blob = SyncCrypto.seal(SyncCrypto.roomKey("code-one"), "secret".toByteArray())
        assertNull(SyncCrypto.open(SyncCrypto.roomKey("code-two"), blob))
    }

    @Test
    fun tamperedBlobFailsToOpen() {
        val key = SyncCrypto.roomKey("k")
        val blob = SyncCrypto.seal(key, "payload".toByteArray())
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte()  // flip a tag bit
        assertNull(SyncCrypto.open(key, blob))
    }
}
