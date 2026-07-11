package com.tacmap.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SafeStoreTest {

    private val testKey = ByteArray(32) { it.toByte() }
    private val label = "waypoints.json"

    @Before fun installTestKey() {
        // Real key lives in the Android Keystore which isn't there on the host
        // JVM, so swap in a fixed one. The envelope code under test is the same.
        SafeStore.keyProvider = SafeStore.KeyProvider { testKey }
    }

    @After fun restoreKeyProvider() {
        SafeStore.keyProvider = SafeStore.KeyProvider { DataKey.key() }
    }

    private fun tempDir(): File = Files.createTempDirectory("safestore").toFile()

    @Test fun writeThenReadRoundTrips() {
        val f = File(tempDir(), "data.json")
        SafeStore.writeAtomically(f, label, "hello world")
        val r = SafeStore.readOrQuarantine(f, label) { it }
        assertTrue(r is SafeStore.LoadResult.Loaded)
        assertEquals("hello world", (r as SafeStore.LoadResult.Loaded).value)
    }

    @Test fun bytesOnDiskAreCiphertextNotPlaintext() {
        val f = File(tempDir(), "data.json")
        SafeStore.writeAtomically(f, label, """[{"callsign":"ZERO","grid":"30UXC1234567890"}]""")
        val onDisk = f.readBytes()
        assertTrue("sealed files carry the magic", SealedEnvelope.isSealedFile(onDisk))
        val text = onDisk.toString(Charsets.ISO_8859_1)
        assertFalse("callsign must not survive in the clear", text.contains("ZERO"))
        assertFalse("grid must not survive in the clear", text.contains("30UXC"))
    }

    @Test fun missingFileIsEmptyNotCorrupt() {
        val f = File(tempDir(), "absent.json")
        val r = SafeStore.readOrQuarantine(f, label) { it }
        assertTrue(r is SafeStore.LoadResult.Empty)
    }

    @Test fun corruptFileIsQuarantinedAndPreserved_notOverwritten() {
        val dir = tempDir()
        val f = File(dir, "data.json")
        f.writeText("{ this is : not valid json")
        val r = SafeStore.readOrQuarantine(f, label) { throw IllegalStateException("decode failed: $it") }
        assertTrue("corrupt file must not be treated as empty", r is SafeStore.LoadResult.Corrupt)
        val corrupt = r as SafeStore.LoadResult.Corrupt
        assertNotNull("original bytes must be preserved aside", corrupt.quarantinedTo)
        assertTrue(corrupt.quarantinedTo!!.exists())
        assertEquals("{ this is : not valid json", corrupt.quarantinedTo!!.readText())
        // The primary path is freed so a fresh seed can be written without
        // clobbering the recovery copy.
        assertTrue(!f.exists())
    }

    @Test fun tamperedSealedFileIsQuarantinedNotSilentlyEmpty() {
        val dir = tempDir()
        val f = File(dir, "data.json")
        SafeStore.writeAtomically(f, label, """{"ok":true}""")
        val bytes = f.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        f.writeBytes(bytes)

        val r = SafeStore.readOrQuarantine(f, label) { it }
        assertTrue("a failed tag check is corruption, not emptiness", r is SafeStore.LoadResult.Corrupt)
        assertTrue((r as SafeStore.LoadResult.Corrupt).quarantinedTo!!.exists())
    }

    @Test fun legacyPlaintextFileIsReadAndSealedInPlace() {
        val dir = tempDir()
        val f = File(dir, "data.json")
        f.writeText("""{"legacy":true}""") // what a pre-encryption build left behind

        val r = SafeStore.readOrQuarantine(f, label) { it }
        assertTrue(r is SafeStore.LoadResult.Loaded)
        assertEquals("""{"legacy":true}""", (r as SafeStore.LoadResult.Loaded).value)

        // Migrated in place: same path, now ciphertext, no orphaned plaintext.
        assertTrue("file should now be sealed", SealedEnvelope.isSealedFile(f.readBytes()))
        assertFalse(f.readBytes().toString(Charsets.ISO_8859_1).contains("legacy"))
        assertNull(dir.listFiles()!!.firstOrNull { it.name.endsWith(".tmp") })
        // and it still reads back
        val again = SafeStore.readOrQuarantine(f, label) { it }
        assertEquals("""{"legacy":true}""", (again as SafeStore.LoadResult.Loaded).value)
    }

    @Test fun lockedKeyLeavesTheFileAloneAndDoesNotQuarantine() {
        val dir = tempDir()
        val f = File(dir, "data.json")
        SafeStore.writeAtomically(f, label, """{"mission":"real"}""")
        val before = f.readBytes()

        SafeStore.keyProvider = SafeStore.KeyProvider { throw DataKey.LockedException() }
        val r = SafeStore.readOrQuarantine(f, label) { it }

        assertTrue("locked is not corrupt", r is SafeStore.LoadResult.Locked)
        assertTrue("file must still be there", f.exists())
        assertTrue("bytes untouched", before.contentEquals(f.readBytes()))
        assertNull("nothing quarantined", dir.listFiles()!!.firstOrNull { it.name.contains(".corrupt-") })
    }

    @Test fun blobFromAnotherStoreIsQuarantinedNotLoaded() {
        val dir = tempDir()
        val f = File(dir, "waypoints.json")
        // Seal under drawings.json, then try to read it as waypoints.json.
        f.writeBytes(SealedEnvelope.sealFile(testKey, """{"shapes":[]}""".toByteArray(), "drawings.json"))
        val r = SafeStore.readOrQuarantine(f, "waypoints.json") { it }
        assertTrue(r is SafeStore.LoadResult.Corrupt)
    }

    @Test fun atomicWriteReplacesPreviousContents() {
        val f = File(tempDir(), "data.json")
        SafeStore.writeAtomically(f, label, "v1")
        SafeStore.writeAtomically(f, label, "v2-longer-content")
        val r = SafeStore.readOrQuarantine(f, label) { it }
        assertEquals("v2-longer-content", (r as SafeStore.LoadResult.Loaded).value)
        // No leftover temp file.
        assertNull(f.parentFile!!.listFiles()!!.firstOrNull { it.name.endsWith(".tmp") })
    }
}
