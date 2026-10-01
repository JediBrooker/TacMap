package com.tacmap.map

import com.tacmap.localization.L10n

import android.content.Context
import com.tacmap.util.SafeStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

@Serializable
internal enum class DocumentImportCopyPhase { COPYING, READY, FAILED }

@Serializable
internal data class DocumentImportCopyState(
    val operationKey: String,
    val phase: DocumentImportCopyPhase,
    val resultPath: String? = null,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    /** sha256:<hex> of the copied bytes, streamed during the copy (nullable: older journals) */
    val contentKey: String? = null,
    /**
     * crash-loop breaker (s9.8): set, durably, right before PDFBox/pdfium look at the
     * copy and cleared after. Still set at launch = that parse killed the process.
     * A nullable field rather than a new phase so an older build still decodes it.
     */
    val inspectStartedAtEpochMs: Long? = null,
    /** that inspection is known to have died: a replay of the same pick must not try again */
    val interrupted: Boolean = false,
)

internal interface DocumentImportCopyStateStore {
    fun state(operationKey: String): DocumentImportCopyState?
    fun persist(state: DocumentImportCopyState)
    /** every journalled operation, the launch check for a stuck inspection marker */
    fun all(): List<DocumentImportCopyState> = emptyList()
}

/** Encrypted process-death journal for Activity-owned document copy operations. */
internal class DocumentImportCopyJournal private constructor(private val file: File) :
    DocumentImportCopyStateStore {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, FILE_NAME))

    @Serializable
    private data class Document(val operations: List<DocumentImportCopyState> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private var unavailableReason: String? = null
    private var document = when (val loaded = SafeStore.readOrQuarantine(file, FILE_NAME) {
        json.decodeFromString<Document>(it)
    }) {
        is SafeStore.LoadResult.Loaded -> loaded.value
        SafeStore.LoadResult.Empty -> Document()
        is SafeStore.LoadResult.Corrupt -> Document().also {
            unavailableReason = L10n.text("The document-import journal could not be authenticated and was preserved.")
        }
        is SafeStore.LoadResult.Locked -> Document().also {
            unavailableReason = L10n.text("The document-import journal is locked. Unlock mission data and retry.")
        }
    }

    @Synchronized
    override fun state(operationKey: String): DocumentImportCopyState? {
        unavailableReason?.let { throw IllegalStateException(it) }
        return document.operations.firstOrNull { it.operationKey == operationKey }
    }

    @Synchronized
    override fun all(): List<DocumentImportCopyState> = if (unavailableReason != null) emptyList() else document.operations

    @Synchronized
    override fun persist(state: DocumentImportCopyState) {
        unavailableReason?.let { throw IllegalStateException(it) }
        val candidate = Document(
            operations = (document.operations.filterNot { it.operationKey == state.operationKey } + state)
                .sortedByDescending { it.updatedAtEpochMs }
                .take(MAX_OPERATIONS),
        )
        SafeStore.writeAtomically(file, FILE_NAME, json.encodeToString(candidate))
        document = candidate
    }

    internal companion object {
        private const val FILE_NAME = "document_import_copy.json"
        private const val MAX_OPERATIONS = 32
        fun forTests(filesDir: File) = DocumentImportCopyJournal(File(filesDir, FILE_NAME))
    }
}

/** the copied file + its streamed content key */
internal data class CopiedDocument(val file: File, val contentKey: String)

/**
 * Stable-destination, atomic document copy that is safe to retry after process
 * death. One pass: copy + SHA-256 (that's the contentKey) + progress, cancel
 * checked every 1 MiB, the .partial registered in flight before it exists so a
 * reconcile can't eat it mid copy (s9.3).
 */
internal class IdempotentDocumentCopy(
    private val destinationDir: File,
    private val extension: String,
    private val maxBytes: Long,
    private val stateStore: DocumentImportCopyStateStore,
    private val openSource: () -> java.io.InputStream,
    private val validate: (File) -> Boolean,
    private val onBytes: (done: Long) -> Unit = {},
    /** throws (CancellationException) to stop; called every CANCEL_GRANULARITY bytes */
    private val checkCancelled: () -> Unit = {},
) {
    fun execute(operationKey: String): File = executeHashed(operationKey).file

    fun executeHashed(operationKey: String): CopiedDocument {
        require(operationKey.isNotBlank()) { "Document import operation key is required" }
        check(destinationDir.exists() || destinationDir.mkdirs()) {
            L10n.text("Could not create the private import directory")
        }
        val stableName = MessageDigest.getInstance("SHA-256")
            .digest(operationKey.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it) }
        val finalFile = File(destinationDir, "import-$stableName.$extension")
        val partialFile = File(destinationDir, "import-$stableName.$extension.partial")
        val prior = stateStore.state(operationKey) // Fail closed if the encrypted journal is unavailable.
        com.tacmap.calibration.InFlightImportFiles.register(partialFile)
        com.tacmap.calibration.InFlightImportFiles.register(finalFile)
        deleteChecked(partialFile)

        if (finalFile.exists()) {
            if (runCatching { validate(finalFile) }.getOrDefault(false)) {
                // a replay after process death: reuse the key the copy streamed, else one read
                val key = prior?.contentKey?.takeIf { prior.resultPath == finalFile.absolutePath }
                    ?: hashOf(finalFile)
                stateStore.persist(
                    DocumentImportCopyState(operationKey, DocumentImportCopyPhase.READY, finalFile.absolutePath, contentKey = key)
                )
                com.tacmap.calibration.InFlightImportFiles.release(partialFile)
                return CopiedDocument(finalFile, key)
            }
            deleteChecked(finalFile)
        }

        stateStore.persist(
            DocumentImportCopyState(operationKey, DocumentImportCopyPhase.COPYING, finalFile.absolutePath)
        )
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            openSource().use { input ->
                FileOutputStream(partialFile).use { output ->
                    input.copyBoundedTo(output, maxBytes, digest)
                    output.flush()
                    output.fd.sync()
                }
            }
            val contentKey = "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
            try {
                Files.move(
                    partialFile.toPath(),
                    finalFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    partialFile.toPath(),
                    finalFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            check(validate(finalFile)) { L10n.text("The copied document is not valid") }
            stateStore.persist(
                DocumentImportCopyState(operationKey, DocumentImportCopyPhase.READY, finalFile.absolutePath, contentKey = contentKey)
            )
            com.tacmap.calibration.InFlightImportFiles.release(partialFile)
            return CopiedDocument(finalFile, contentKey)
        } catch (failure: Throwable) {
            runCatching { deleteChecked(partialFile) }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { deleteChecked(finalFile) }.exceptionOrNull()?.let(failure::addSuppressed)
            com.tacmap.calibration.InFlightImportFiles.release(partialFile)
            com.tacmap.calibration.InFlightImportFiles.release(finalFile)
            runCatching {
                stateStore.persist(DocumentImportCopyState(operationKey, DocumentImportCopyPhase.FAILED))
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun java.io.InputStream.copyBoundedTo(output: java.io.OutputStream, limit: Long, digest: MessageDigest) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        var sinceCheck = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw ImportTooLargeException(limit)
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            sinceCheck += count
            if (sinceCheck >= CANCEL_GRANULARITY) {
                sinceCheck = 0
                checkCancelled()
                onBytes(total)
            }
        }
        onBytes(total)
    }

    private fun hashOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var sinceCheck = 0L
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                sinceCheck += n
                if (sinceCheck >= CANCEL_GRANULARITY) { sinceCheck = 0; checkCancelled() }
            }
        }
        return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val CANCEL_GRANULARITY = com.tacmap.calibration.ImportLimits.CANCEL_COPY_GRANULARITY_BYTES
    }

    private fun deleteChecked(file: File) {
        check(!file.exists() || file.delete() || !file.exists()) {
            "Could not remove incomplete import ${file.name}"
        }
    }
}

/** the source ran past the import limit while copying (its size wasn't known up front) */
internal class ImportTooLargeException(val limit: Long) : Exception("Import exceeds the supported size limit")
