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
)

internal interface DocumentImportCopyStateStore {
    fun state(operationKey: String): DocumentImportCopyState?
    fun persist(state: DocumentImportCopyState)
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

/** Stable-destination, atomic document copy that is safe to retry after process death. */
internal class IdempotentDocumentCopy(
    private val destinationDir: File,
    private val extension: String,
    private val maxBytes: Long,
    private val stateStore: DocumentImportCopyStateStore,
    private val openSource: () -> java.io.InputStream,
    private val validate: (File) -> Boolean,
) {
    fun execute(operationKey: String): File {
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
        stateStore.state(operationKey) // Fail closed if the encrypted journal is unavailable.
        deleteChecked(partialFile)

        if (finalFile.exists()) {
            if (runCatching { validate(finalFile) }.getOrDefault(false)) {
                stateStore.persist(
                    DocumentImportCopyState(operationKey, DocumentImportCopyPhase.READY, finalFile.absolutePath)
                )
                return finalFile
            }
            deleteChecked(finalFile)
        }

        stateStore.persist(
            DocumentImportCopyState(operationKey, DocumentImportCopyPhase.COPYING, finalFile.absolutePath)
        )
        try {
            openSource().use { input ->
                FileOutputStream(partialFile).use { output ->
                    input.copyBoundedTo(output, maxBytes)
                    output.flush()
                    output.fd.sync()
                }
            }
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
                DocumentImportCopyState(operationKey, DocumentImportCopyPhase.READY, finalFile.absolutePath)
            )
            return finalFile
        } catch (failure: Throwable) {
            runCatching { deleteChecked(partialFile) }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { deleteChecked(finalFile) }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching {
                stateStore.persist(DocumentImportCopyState(operationKey, DocumentImportCopyPhase.FAILED))
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun java.io.InputStream.copyBoundedTo(output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { L10n.text("Import exceeds the supported size limit") }
            output.write(buffer, 0, count)
        }
    }

    private fun deleteChecked(file: File) {
        check(!file.exists() || file.delete() || !file.exists()) {
            "Could not remove incomplete import ${file.name}"
        }
    }
}
