package com.tacmap.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking

class ExportPipelineTest {
    @Test
    fun overlappingExportsUseOpaqueIndependentGenerationsAndCleanupCannotCrossThem() {
        val root = Files.createTempDirectory("export-generations").toFile()
        val tokens = listOf(
            "00000000000000000000000000000001",
            "00000000000000000000000000000002",
            "00000000000000000000000000000003",
            "00000000000000000000000000000004",
        )
        var tokenIndex = 0
        val workspace = ExportArtifactWorkspace(root) { tokens[tokenIndex++] }

        val first = workspace.prepareArtifact("TacMap.geojson").also {
            it.partialFile.writeText("first-partial")
            it.finalFile.writeText("first")
        }
        val second = workspace.prepareArtifact("TacMap.geojson").also {
            it.partialFile.writeText("second-partial")
            it.finalFile.writeText("second")
        }
        val firstGeneration = requireNotNull(first.finalFile.parentFile)
        val secondGeneration = requireNotNull(second.finalFile.parentFile)

        assertNotEquals(firstGeneration, secondGeneration)
        assertNotEquals(first.finalFile.name, second.finalFile.name)
        assertFalse(first.finalFile.name.contains("TacMap", ignoreCase = true))
        assertFalse(firstGeneration.name.contains("TacMap", ignoreCase = true))
        assertTrue(first.finalFile.extension == "geojson")
        assertTrue(second.finalFile.exists())

        workspace.cleanupArtifact(first)

        assertFalse(firstGeneration.exists())
        assertTrue(secondGeneration.exists())
        assertEquals("second", second.finalFile.readText())
        assertTrue(second.partialFile.exists())
    }

    @Test
    fun staleCleanupRemovesOnlyExpiredGenerationsAndLegacyFlatFiles() {
        val root = Files.createTempDirectory("export-retention").toFile()
        val tokens = listOf(
            "10000000000000000000000000000001",
            "10000000000000000000000000000002",
            "10000000000000000000000000000003",
            "10000000000000000000000000000004",
        )
        var tokenIndex = 0
        val workspace = ExportArtifactWorkspace(root) { tokens[tokenIndex++] }
        val expired = workspace.prepareArtifact("TacMap-track.gpx").also {
            it.finalFile.writeText("expired")
        }
        val active = workspace.prepareArtifact("TacMap-track.gpx").also {
            it.finalFile.writeText("active")
        }
        val legacyFlatFile = File(root, "TacMap-track.gpx").also { it.writeText("legacy") }
        val now = 5_000_000L
        val expiredGeneration = requireNotNull(expired.finalFile.parentFile)
        val activeGeneration = requireNotNull(active.finalFile.parentFile)
        assertTrue(
            expiredGeneration.setLastModified(
                now - EXPORT_ARTIFACT_RETENTION_MS - 1L,
            )
        )
        assertTrue(activeGeneration.setLastModified(now))

        workspace.cleanupStaleArtifacts(
            nowMillis = now,
            retentionMillis = EXPORT_ARTIFACT_RETENTION_MS,
        )

        assertFalse(expiredGeneration.exists())
        assertFalse(legacyFlatFile.exists())
        assertTrue(active.finalFile.exists())
        assertEquals("active", active.finalFile.readText())
    }

    @Test
    fun successRunsEveryStageAndLeavesCleanupScheduled() {
        val driver = FakeDriver()

        val result = runPipeline("GeoJSON", driver)

        assertTrue(result.succeeded)
        assertEquals(
            listOf("cleanup-stale", "generate", "prepare", "write", "uri", "schedule-cleanup", "launch"),
            driver.calls,
        )
        assertTrue(driver.artifact.finalFile.exists())
        assertFalse(driver.artifact.partialFile.exists())
    }

    @Test
    fun everyFailureStageReturnsActionableErrorAndRemovesPartialArtifacts() {
        val failureCalls = listOf(
            "cleanup-stale" to ExportStage.CLEANUP,
            "generate" to ExportStage.GENERATION,
            "prepare" to ExportStage.CACHE_PREPARATION,
            "write" to ExportStage.WRITE,
            "uri" to ExportStage.SHARE_URI,
            "schedule-cleanup" to ExportStage.CLEANUP,
            "launch" to ExportStage.SHARE_LAUNCH,
        )

        failureCalls.forEach { (failureCall, expectedStage) ->
            val driver = FakeDriver(failAt = failureCall)
            val result = runPipeline("GeoJSON", driver)

            assertFalse("$failureCall must fail", result.succeeded)
            assertEquals(expectedStage, result.failedStage)
            assertTrue("$failureCall should tell the user what to do", result.message.contains("try again"))
            assertTrue("$failureCall must invoke cleanup", "cleanup-failed" in driver.calls)
            assertFalse("$failureCall left a partial file", driver.artifact.partialFile.exists())
            assertFalse("$failureCall left a final file", driver.artifact.finalFile.exists())
        }
    }

    @Test
    fun cleanupFailureIsReportedWithoutMaskingPrimaryStage() {
        val driver = FakeDriver(failAt = "write", failCleanup = true)

        val result = runPipeline("GPX track", driver)

        assertFalse(result.succeeded)
        assertEquals(ExportStage.WRITE, result.failedStage)
        assertTrue(result.message.contains("cleanup also failed"))
    }

    @Test
    fun generationAndFsyncFileStagesRunOnBackgroundDispatcher() = runBlocking {
        val driver = FakeDriver()
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "export-file-worker")
        }.asCoroutineDispatcher().use { fileDispatcher ->
            val result = executeExportPipeline(
                exportLabel = "GeoJSON",
                driver = driver,
                fileDispatcher = fileDispatcher,
                launchDispatcher = Dispatchers.Unconfined,
            )
            assertTrue(result.succeeded)
        }
        assertTrue(driver.threadByCall.getValue("generate").startsWith("export-file-worker"))
        assertTrue(driver.threadByCall.getValue("write").startsWith("export-file-worker"))
        assertFalse(driver.threadByCall.getValue("launch").startsWith("export-file-worker"))
    }

    private fun <T> runPipeline(label: String, driver: ExportPipelineDriver<T>) = runBlocking {
        executeExportPipeline(
            exportLabel = label,
            driver = driver,
            fileDispatcher = Dispatchers.Unconfined,
            launchDispatcher = Dispatchers.Unconfined,
        )
    }

    private class FakeDriver(
        private val failAt: String? = null,
        private val failCleanup: Boolean = false,
    ) : ExportPipelineDriver<String> {
        val directory: File = Files.createTempDirectory("export-pipeline").toFile()
        val artifact = ExportArtifact(
            finalFile = File(directory, "TacMap.geojson"),
            partialFile = File(directory, "TacMap.geojson.partial"),
        )
        val calls = mutableListOf<String>()
        val threadByCall = mutableMapOf<String, String>()

        override fun cleanupStaleArtifacts() {
            called("cleanup-stale")
        }

        override fun generateContent(): String {
            called("generate")
            return "payload"
        }

        override fun prepareArtifact(): ExportArtifact {
            artifact.partialFile.writeText("partial")
            called("prepare")
            return artifact
        }

        override fun writeArtifact(artifact: ExportArtifact, content: String) {
            artifact.partialFile.writeText(content)
            called("write")
            artifact.partialFile.copyTo(artifact.finalFile, overwrite = true)
            artifact.partialFile.delete()
        }

        override fun createShareToken(artifact: ExportArtifact): String {
            called("uri")
            return artifact.finalFile.absolutePath
        }

        override fun scheduleCleanup(artifact: ExportArtifact) {
            called("schedule-cleanup")
        }

        override fun launchShare(artifact: ExportArtifact, token: String) {
            called("launch")
        }

        override fun cleanupFailedArtifact(artifact: ExportArtifact?) {
            calls += "cleanup-failed"
            this.artifact.partialFile.delete()
            this.artifact.finalFile.delete()
            if (failCleanup) error("cleanup unavailable")
        }

        private fun called(name: String) {
            calls += name
            threadByCall[name] = Thread.currentThread().name
            if (name == failAt) error("simulated $name failure")
        }
    }
}
