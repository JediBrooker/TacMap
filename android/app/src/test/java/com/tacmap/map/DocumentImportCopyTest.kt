package com.tacmap.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class DocumentImportCopyTest {
    @Test
    fun processDeathRetryReusesStableValidatedResultWithoutDuplicateCopy() {
        val dir = Files.createTempDirectory("document-copy-retry").toFile()
        val journal = MemoryJournal()
        var opens = 0
        fun coordinator() = IdempotentDocumentCopy(
            destinationDir = dir,
            extension = "pdf",
            maxBytes = 1024,
            stateStore = journal,
            openSource = {
                opens++
                ByteArrayInputStream("valid-pdf".toByteArray())
            },
            validate = { it.readText() == "valid-pdf" },
        )

        val first = coordinator().execute("pdf:stable-operation")
        val afterProcessDeath = coordinator().execute("pdf:stable-operation")

        assertEquals(first.canonicalPath, afterProcessDeath.canonicalPath)
        assertEquals(1, opens)
        assertEquals(listOf(first.name), dir.listFiles()!!.map(File::getName))
        assertEquals(DocumentImportCopyPhase.READY, journal.state("pdf:stable-operation")!!.phase)
        assertEquals(first.absolutePath, journal.state("pdf:stable-operation")!!.resultPath)
    }

    @Test
    fun invalidOrFailedCopyLeavesNoFinalOrPartialResidue() {
        val dir = Files.createTempDirectory("document-copy-invalid").toFile()
        val journal = MemoryJournal()
        val operation = IdempotentDocumentCopy(
            destinationDir = dir,
            extension = "mbtiles",
            maxBytes = 1024,
            stateStore = journal,
            openSource = { ByteArrayInputStream("not-sqlite".toByteArray()) },
            validate = { false },
        )

        assertTrue(runCatching { operation.execute("mbtiles:invalid") }.isFailure)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        assertEquals(DocumentImportCopyPhase.FAILED, journal.state("mbtiles:invalid")!!.phase)

        val tooLarge = IdempotentDocumentCopy(
            destinationDir = dir,
            extension = "pdf",
            maxBytes = 2,
            stateStore = journal,
            openSource = { ByteArrayInputStream("too-large".toByteArray()) },
            validate = { true },
        )
        assertTrue(runCatching { tooLarge.execute("pdf:too-large") }.isFailure)
        assertFalse(dir.listFiles().orEmpty().any { it.name.endsWith(".partial") })
        assertFalse(dir.listFiles().orEmpty().any { it.extension == "pdf" })
    }

    private class MemoryJournal : DocumentImportCopyStateStore {
        private val states = mutableMapOf<String, DocumentImportCopyState>()
        override fun state(operationKey: String): DocumentImportCopyState? = states[operationKey]
        override fun persist(state: DocumentImportCopyState) {
            states[state.operationKey] = state
        }
    }
}
