package com.tacmap.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * OD-F4 on the JVM: a restore keeps a content bound session whose PDF is missing, the
 * bytes check fails it (missing, swapped) until the right file is back, and the reconcile
 * reaps nothing while the file is gone so recovery still works
 */
class PdfStoredFileTest {
    private fun tempDir(): File = Files.createTempDirectory("odf4").toFile().apply { deleteOnExit() }

    @Test
    fun restoreVerdictKeepsOnlyContentBoundSessionsWithAMissingFile() {
        assertEquals(StoredFileVerdict.RESTORE, storedFileVerdict(validPath = true, fileThere = true, contentBound = true))
        assertEquals(StoredFileVerdict.RESTORE, storedFileVerdict(validPath = true, fileThere = true, contentBound = false))
        assertEquals(StoredFileVerdict.KEEP_UNAVAILABLE, storedFileVerdict(validPath = true, fileThere = false, contentBound = true))
        // a legacy record can't prove which bytes it was for, and a bad path is never trusted
        assertEquals(StoredFileVerdict.REJECT, storedFileVerdict(validPath = true, fileThere = false, contentBound = false))
        assertEquals(StoredFileVerdict.REJECT, storedFileVerdict(validPath = false, fileThere = true, contentBound = true))
        assertEquals(StoredFileVerdict.REJECT, storedFileVerdict(validPath = false, fileThere = false, contentBound = true))
    }

    // the memo is keyed on path+size+mtime+inode. on linux CI a delete+rewrite inside the same
    // ms can land on the same inode and mtime, so the old hash comes back and this flaked (3.0.0
    // main CI too). real swaps are a reimport, way more than a ms apart, so pin distinct mtimes here
    private var tick = 1_700_000_000_000L
    private fun File.writeStamped(bytes: ByteArray) {
        writeBytes(bytes)
        tick += 10_000
        assertTrue(setLastModified(tick))
    }

    @Test
    fun missingThenSwappedThenRestoredBytes() {
        val dir = tempDir()
        val pdf = File(dir, "import-abc.pdf")
        val original = "%PDF-1.4 the sheet it was calibrated on".toByteArray()
        pdf.writeStamped(original)
        val key = PdfStoredFile.contentKey(pdf)!!
        assertEquals(PdfCalibrationIdentity.contentKey(pdf), key)
        assertTrue(PdfStoredFile.matches(pdf, key))

        // missing
        assertTrue(pdf.delete())
        assertFalse("missing file", PdfStoredFile.matches(pdf, key))

        // swapped: same name, same size, different bytes
        val swapped = original.copyOf().also { it[it.size - 1] = 'X'.code.toByte() }
        assertEquals(original.size, swapped.size)
        pdf.writeStamped(swapped)
        assertFalse("swapped bytes never pass for the old calibration", PdfStoredFile.matches(pdf, key))

        // the right file back: Try Again / next launch recovers
        assertTrue(pdf.delete())
        pdf.writeStamped(original)
        assertTrue("restored", PdfStoredFile.matches(pdf, key))
    }

    @Test
    fun coldRestoreHashesTheFileOnceWhoeverAsksAtTheSameTime() {
        // AND-R2-3: the page open, the new source and attachBake all ask on a cold restore
        val pdf = File(tempDir(), "import-sf.pdf").apply { writeBytes(ByteArray(256 * 1024) { it.toByte() }) }
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val gate = java.util.concurrent.CountDownLatch(1)
        val before = PdfStoredFile.hasher
        PdfStoredFile.hasher = { f ->
            calls.incrementAndGet()
            gate.await(5, java.util.concurrent.TimeUnit.SECONDS)
            PdfCalibrationIdentity.contentKey(f)
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val asks = (0 until 4).map { pool.submit<String?> { PdfStoredFile.contentKey(pdf) } }
            // let them all pile up behind the first one
            Thread.sleep(300)
            gate.countDown()
            val keys = asks.map { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(1, calls.get())
            assertEquals(setOf(PdfCalibrationIdentity.contentKey(pdf)), keys.toSet())
            // later checks are the memo, no hash
            assertTrue(PdfStoredFile.matches(pdf, keys[0]!!))
            assertEquals(1, calls.get())
            // different bytes are a different stamp, hashed again
            pdf.writeBytes(ByteArray(1000) { 7 })
            assertFalse(PdfStoredFile.matches(pdf, keys[0]!!))
            assertEquals(2, calls.get())
        } finally {
            pool.shutdownNow()
            PdfStoredFile.hasher = before
        }
    }

    @Test
    fun reconcileReapsNothingWhileTheSessionsPdfIsMissing() {
        val root = tempDir()
        val pdfs = File(root, "pdf_maps").apply { mkdirs() }
        val tiles = File(root, "offline_tiles").apply { mkdirs() }
        val missing = File(pdfs, "import-gone.pdf")
        val bake = File(tiles, "tacmap-bake-1.mbtiles").apply { writeText("tiles") }
        val other = File(pdfs, "import-other.pdf").apply { writeText("other") }
        val done = ManagedImportedMapFileLifecycle.reconcile(
            managedParent = root,
            directories = listOf(pdfs, tiles),
            keeping = setOf(missing, bake),
        )
        // same as iOS: a keep entry that isn't there makes the pass refuse, so the bake
        // (and everything else) survives until the file is back
        assertFalse(done)
        assertTrue(bake.isFile)
        assertTrue(other.isFile)
    }
}
