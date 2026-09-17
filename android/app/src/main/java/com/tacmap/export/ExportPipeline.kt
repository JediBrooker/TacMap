package com.tacmap.export

import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class ExportStage {
    CLEANUP,
    GENERATION,
    CACHE_PREPARATION,
    WRITE,
    SHARE_URI,
    SHARE_LAUNCH,
}

internal data class ExportArtifact(
    val finalFile: File,
    val partialFile: File,
)

/**
 * Owns short-lived export files without ever reusing a path or content URI.
 *
 * Each export receives a cryptographically opaque generation directory and an
 * independently opaque filename. Cleanup is deliberately generation-scoped:
 * one share can never remove or replace the files backing another share.
 */
internal class ExportArtifactWorkspace(
    private val rootDirectory: File,
    private val opaqueToken: () -> String = ::newOpaqueExportToken,
) {
    fun prepareArtifact(suggestedFileName: String): ExportArtifact {
        ensureRootDirectory()
        val extension = safeExtension(suggestedFileName)
        repeat(MAX_GENERATION_ATTEMPTS) {
            val generationToken = requireOpaqueToken(opaqueToken())
            val generationDirectory = File(
                rootDirectory,
                "$GENERATION_DIRECTORY_PREFIX$generationToken",
            )
            if (!generationDirectory.mkdir()) {
                if (generationDirectory.exists()) return@repeat
                error("Could not create an isolated export directory")
            }
            return try {
                val fileToken = requireOpaqueToken(opaqueToken())
                val finalFile = File(
                    generationDirectory,
                    "$EXPORT_FILE_PREFIX$fileToken.$extension",
                )
                ExportArtifact(
                    finalFile = finalFile,
                    partialFile = File(generationDirectory, "${finalFile.name}.partial"),
                )
            } catch (failure: Throwable) {
                check(generationDirectory.delete() || !generationDirectory.exists()) {
                    "Could not remove an incomplete export directory"
                }
                throw failure
            }
        }
        error("Could not allocate a unique export directory")
    }

    fun cleanupArtifact(artifact: ExportArtifact) {
        ensureRootDirectory()
        val generationDirectory = requireManagedGeneration(artifact.finalFile.parentFile)
        val partialDirectory = requireNotNull(artifact.partialFile.parentFile) {
            "Partial export has no generation directory"
        }
        check(partialDirectory.normalizedPath() == generationDirectory.normalizedPath()) {
            "Export files must belong to the same isolated directory"
        }
        cleanupGenerationDirectory(generationDirectory)
    }

    fun cleanupStaleArtifacts(
        nowMillis: Long = System.currentTimeMillis(),
        retentionMillis: Long = EXPORT_ARTIFACT_RETENTION_MS,
    ) {
        require(retentionMillis >= 0L) { "Export retention must not be negative" }
        ensureRootDirectory()
        rootDirectory.listFiles().orEmpty().forEach { candidate ->
            when {
                Files.isSymbolicLink(candidate.toPath()) -> deleteExactEntry(candidate)
                candidate.isFile -> {
                    // One-time cleanup for legacy fixed-name exports. New exports
                    // are always stored in isolated generation directories.
                    deleteExactEntry(candidate)
                }
                candidate.isDirectory && isManagedGeneration(candidate) -> {
                    val ageMillis = (nowMillis - candidate.lastModified()).coerceAtLeast(0L)
                    if (ageMillis >= retentionMillis) cleanupGenerationDirectory(candidate)
                }
            }
        }
    }

    private fun ensureRootDirectory() {
        check(rootDirectory.exists() || rootDirectory.mkdirs()) {
            "Could not create the export cache directory"
        }
        check(rootDirectory.isDirectory) { "Export cache path is not a directory" }
        check(!Files.isSymbolicLink(rootDirectory.toPath())) {
            "Export cache directory must not be a symbolic link"
        }
    }

    private fun cleanupGenerationDirectory(directory: File) {
        val managedDirectory = requireManagedGeneration(directory)
        check(!Files.isSymbolicLink(managedDirectory.toPath())) {
            "Export generation directory must not be a symbolic link"
        }
        managedDirectory.listFiles().orEmpty().forEach(::deleteExactEntry)
        check(managedDirectory.delete() || !managedDirectory.exists()) {
            "Could not remove ${managedDirectory.name}"
        }
    }

    private fun deleteExactEntry(entry: File) {
        // Files.deleteIfExists removes a symbolic link itself and never follows
        // it. Refuse recursive deletion; a generation should contain files only.
        check(!entry.isDirectory || Files.isSymbolicLink(entry.toPath()) || entry.list().isNullOrEmpty()) {
            "Unexpected nested export directory ${entry.name}"
        }
        check(Files.deleteIfExists(entry.toPath()) || !entry.exists()) {
            "Could not remove ${entry.name}"
        }
    }

    private fun requireManagedGeneration(directory: File?): File {
        val candidate = requireNotNull(directory) { "Export has no generation directory" }
        check(isManagedGeneration(candidate)) { "Export is outside its isolated directory" }
        return candidate
    }

    private fun isManagedGeneration(directory: File): Boolean =
        directory.parentFile?.normalizedPath() == rootDirectory.normalizedPath() &&
            GENERATION_DIRECTORY_PATTERN.matches(directory.name)

    private fun File.normalizedPath() = absoluteFile.toPath().normalize()

    private fun safeExtension(suggestedFileName: String): String =
        suggestedFileName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .takeIf(SAFE_EXTENSION_PATTERN::matches)
            ?: DEFAULT_EXPORT_EXTENSION

    private fun requireOpaqueToken(token: String): String {
        require(OPAQUE_TOKEN_PATTERN.matches(token)) { "Invalid opaque export token" }
        return token
    }

    private companion object {
        const val MAX_GENERATION_ATTEMPTS = 8
        const val GENERATION_DIRECTORY_PREFIX = "generation-"
        const val EXPORT_FILE_PREFIX = "export-"
        const val DEFAULT_EXPORT_EXTENSION = "txt"
        val GENERATION_DIRECTORY_PATTERN = Regex("generation-[0-9a-f]{32}")
        val OPAQUE_TOKEN_PATTERN = Regex("[0-9a-f]{32}")
        val SAFE_EXTENSION_PATTERN = Regex("[a-z0-9]{1,16}")
    }
}

internal const val EXPORT_ARTIFACT_RETENTION_MS = 15 * 60 * 1000L

private val exportTokenRandom = SecureRandom()

private fun newOpaqueExportToken(): String {
    val bytes = ByteArray(16).also(exportTokenRandom::nextBytes)
    val hex = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(hex[unsigned ushr 4])
            append(hex[unsigned and 0x0f])
        }
    }
}

internal data class ExportPipelineResult(
    val succeeded: Boolean,
    val message: String,
    val failedStage: ExportStage? = null,
)

/** Small injectable boundary so every export stage has deterministic failure tests. */
internal interface ExportPipelineDriver<ShareToken> {
    fun cleanupStaleArtifacts()
    fun generateContent(): String
    fun prepareArtifact(): ExportArtifact
    fun writeArtifact(artifact: ExportArtifact, content: String)
    fun createShareToken(artifact: ExportArtifact): ShareToken
    fun scheduleCleanup(artifact: ExportArtifact)
    fun launchShare(artifact: ExportArtifact, token: ShareToken)
    fun cleanupFailedArtifact(artifact: ExportArtifact?)
}

internal suspend fun <ShareToken> executeExportPipeline(
    exportLabel: String,
    driver: ExportPipelineDriver<ShareToken>,
    fileDispatcher: CoroutineDispatcher = Dispatchers.IO,
    launchDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
): ExportPipelineResult {
    var artifact: ExportArtifact? = null
    suspend fun <T> atStage(stage: ExportStage, operation: suspend () -> T): T = try {
        operation()
    } catch (failure: Throwable) {
        throw ExportPipelineFailure(stage, failure)
    }
    return try {
        atStage(ExportStage.CLEANUP) {
            withContext(fileDispatcher) { driver.cleanupStaleArtifacts() }
        }
        val content = atStage(ExportStage.GENERATION) {
            withContext(fileDispatcher) { driver.generateContent() }
        }
        val prepared = atStage(ExportStage.CACHE_PREPARATION) {
            withContext(fileDispatcher) { driver.prepareArtifact() }
        }
        artifact = prepared
        atStage(ExportStage.WRITE) {
            withContext(fileDispatcher) { driver.writeArtifact(prepared, content) }
        }
        val shareToken = atStage(ExportStage.SHARE_URI) {
            withContext(fileDispatcher) { driver.createShareToken(prepared) }
        }
        // Register cleanup before handing control to another app. If launching
        // fails, the catch path also removes both final and partial files now.
        atStage(ExportStage.CLEANUP) {
            withContext(launchDispatcher) { driver.scheduleCleanup(prepared) }
        }
        atStage(ExportStage.SHARE_LAUNCH) {
            withContext(launchDispatcher) { driver.launchShare(prepared, shareToken) }
        }
        ExportPipelineResult(true, "$exportLabel is ready to share")
    } catch (wrapped: ExportPipelineFailure) {
        val stage = wrapped.stage
        val failure = wrapped.cause ?: wrapped
        val cleanupFailure = try {
            withContext(fileDispatcher) { driver.cleanupFailedArtifact(artifact) }
            null
        } catch (cleanup: Throwable) {
            cleanup
        }
        val detail = failure.message?.takeIf { it.isNotBlank() }
        val base = when (stage) {
            ExportStage.CLEANUP ->
                "Could not clear temporary export files. Restart TacMap and try again."
            ExportStage.GENERATION ->
                "Could not generate $exportLabel. Check the mission data and try again."
            ExportStage.CACHE_PREPARATION, ExportStage.WRITE ->
                "Could not write $exportLabel to temporary storage. Free some space and try again."
            ExportStage.SHARE_URI ->
                "Could not create a secure share link for $exportLabel. Restart TacMap and try again."
            ExportStage.SHARE_LAUNCH ->
                "No compatible app could share $exportLabel. Install or enable a file-sharing app and try again."
        }
        val cleanupNote = if (cleanupFailure != null) {
            " Temporary-file cleanup also failed; restarting TacMap will retry cleanup."
        } else {
            ""
        }
        ExportPipelineResult(
            succeeded = false,
            message = buildString {
                append(base)
                if (detail != null) append(" ($detail)")
                append(cleanupNote)
            },
            failedStage = stage,
        )
    }
}

private class ExportPipelineFailure(
    val stage: ExportStage,
    cause: Throwable,
) : RuntimeException(cause)
