package com.tacmap.map

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

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

    @Test
    fun theCopyStreamsTheContentKeyInTheSamePass() {
        // s9.3: one pass, copy + sha256 + progress, no second full read
        val dir = Files.createTempDirectory("document-copy-hash").toFile()
        val bytes = ByteArray(3 * 1024 * 1024 + 512 * 1024) { (it * 31 % 251).toByte() }
        val progress = ArrayList<Long>()
        var reads = 0
        val copied = IdempotentDocumentCopy(
            destinationDir = dir,
            extension = "pdf",
            maxBytes = com.tacmap.calibration.ImportLimits.PDF_MAX_BYTES,
            stateStore = MemoryJournal(),
            openSource = { reads++; ByteArrayInputStream(bytes) },
            validate = { true },
            onBytes = { progress += it },
        ).executeHashed("pdf:hash")
        val want = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(want, copied.contentKey)
        // same format the old main thread PdfCalibrationIdentity hash gave, so dedupe still matches
        assertEquals(com.tacmap.calibration.PdfCalibrationIdentity.contentKey(copied.file), copied.contentKey)
        assertEquals(1, reads)
        // progress every 1 MiB and once at the end with the total
        assertEquals(listOf(1L, 2L, 3L).map { it * 1024 * 1024 } + bytes.size.toLong(), progress)
    }

    @Test
    fun cancelStopsAtTheNextMebibyteAndLeavesNothingBehind() {
        val dir = Files.createTempDirectory("document-copy-cancel").toFile()
        val journal = MemoryJournal()
        val bytes = ByteArray(5 * 1024 * 1024)
        var checks = 0
        val op = IdempotentDocumentCopy(
            destinationDir = dir,
            extension = "pdf",
            maxBytes = com.tacmap.calibration.ImportLimits.PDF_MAX_BYTES,
            stateStore = journal,
            openSource = { ByteArrayInputStream(bytes) },
            validate = { true },
            checkCancelled = { if (++checks == 2) throw CancellationException("cancelled") },
        )
        val failure = runCatching { op.executeHashed("pdf:cancel") }.exceptionOrNull()
        assertTrue("$failure", failure is CancellationException)
        assertEquals(2, checks)
        assertTrue("no .partial or final copy left", dir.listFiles().orEmpty().isEmpty())
        assertEquals(DocumentImportCopyPhase.FAILED, journal.state("pdf:cancel")!!.phase)
        assertTrue(com.tacmap.calibration.InFlightImportFiles.snapshot().none { it.parentFile == dir.absoluteFile })
    }

    @Test
    fun anOlderJournalWithoutTheNewFieldsStillDecodes() {
        // the crash marker is a nullable field, not a new phase, so builds either side read it
        val old = """{"operationKey":"pdf:x","phase":"READY","resultPath":"/data/x.pdf","updatedAtEpochMs":5}"""
        val json = Json { ignoreUnknownKeys = true }
        val st = json.decodeFromString(DocumentImportCopyState.serializer(), old)
        assertEquals(DocumentImportCopyPhase.READY, st.phase)
        assertNull(st.contentKey)
        assertNull(st.inspectStartedAtEpochMs)
        assertFalse(st.interrupted)
        // and a marked one round trips
        val marked = st.copy(inspectStartedAtEpochMs = 9L, contentKey = "sha256:aa")
        assertEquals(marked, json.decodeFromString(DocumentImportCopyState.serializer(), Json.encodeToString(DocumentImportCopyState.serializer(), marked)))
    }

    @Test
    fun aMarkerLeftAtLaunchMeansTheParseKilledUsDropItAndDontRetry() {
        // s9.8 crash-loop breaker
        val dir = Files.createTempDirectory("document-copy-marker").toFile()
        val copy = File(dir, "import-1.pdf").apply { writeText("x") }
        val partial = File(dir, "import-1.pdf.partial").apply { writeText("x") }
        val journal = MemoryJournal()
        journal.persist(DocumentImportCopyState("pdf:dead", DocumentImportCopyPhase.READY, copy.absolutePath, inspectStartedAtEpochMs = 3L))
        journal.persist(DocumentImportCopyState("pdf:fine", DocumentImportCopyPhase.READY, "/elsewhere"))
        assertTrue(MapImportPipeline.sweepInterrupted(journal))
        assertFalse(copy.exists())
        assertFalse(partial.exists())
        val dead = journal.state("pdf:dead")!!
        assertNull(dead.inspectStartedAtEpochMs)
        assertTrue(dead.interrupted)
        assertEquals(DocumentImportCopyPhase.FAILED, dead.phase)
        assertEquals(DocumentImportCopyPhase.READY, journal.state("pdf:fine")!!.phase)
        // nothing stuck the second time round, no second alert
        assertFalse(MapImportPipeline.sweepInterrupted(journal))
    }

    private class MemoryJournal : DocumentImportCopyStateStore {
        private val states = mutableMapOf<String, DocumentImportCopyState>()
        override fun state(operationKey: String): DocumentImportCopyState? = states[operationKey]
        override fun persist(state: DocumentImportCopyState) {
            states[state.operationKey] = state
        }
        override fun all(): List<DocumentImportCopyState> = states.values.toList()
    }
}
