package com.tacmap.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SafeStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("safestore").toFile()

    @Test fun writeThenReadRoundTrips() {
        val f = File(tempDir(), "data.json")
        SafeStore.writeAtomically(f, "hello world")
        val r = SafeStore.readOrQuarantine(f) { it }
        assertTrue(r is SafeStore.LoadResult.Loaded)
        assertEquals("hello world", (r as SafeStore.LoadResult.Loaded).value)
    }

    @Test fun missingFileIsEmptyNotCorrupt() {
        val f = File(tempDir(), "absent.json")
        val r = SafeStore.readOrQuarantine(f) { it }
        assertTrue(r is SafeStore.LoadResult.Empty)
    }

    @Test fun corruptFileIsQuarantinedAndPreserved_notOverwritten() {
        val dir = tempDir()
        val f = File(dir, "data.json")
        f.writeText("{ this is : not valid json")
        val r = SafeStore.readOrQuarantine(f) { throw IllegalStateException("decode failed: $it") }
        assertTrue("corrupt file must not be treated as empty", r is SafeStore.LoadResult.Corrupt)
        val corrupt = r as SafeStore.LoadResult.Corrupt
        assertNotNull("original bytes must be preserved aside", corrupt.quarantinedTo)
        assertTrue(corrupt.quarantinedTo!!.exists())
        assertEquals("{ this is : not valid json", corrupt.quarantinedTo!!.readText())
        // The primary path is freed so a fresh seed can be written without
        // clobbering the recovery copy.
        assertTrue(!f.exists())
    }

    @Test fun atomicWriteReplacesPreviousContents() {
        val f = File(tempDir(), "data.json")
        SafeStore.writeAtomically(f, "v1")
        SafeStore.writeAtomically(f, "v2-longer-content")
        assertEquals("v2-longer-content", f.readText())
        // No leftover temp file.
        assertNull(f.parentFile!!.listFiles()!!.firstOrNull { it.name.endsWith(".tmp") })
    }
}
