package com.tacmap.app

import java.io.InputStream

/**
 * "Add a map" shows one picker for both map formats, so the file's own header has to
 * say which pipeline runs. Extensions and mime types off the picker are unreliable
 * (Drive hands back octet-stream for everything), the magic bytes are not.
 */
internal object MapFileSniffer {
    /** enough for the sqlite magic, the pdf one is shorter */
    const val HEADER_BYTES = 16

    private val PDF_MAGIC = "%PDF".toByteArray(Charsets.US_ASCII)
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /** PDF or MBTILES off the leading bytes, null for anything else (empty included) */
    fun kindOf(header: ByteArray): DocumentImportKind? = when {
        header.startsWith(PDF_MAGIC) -> DocumentImportKind.PDF
        header.startsWith(SQLITE_MAGIC) -> DocumentImportKind.MBTILES
        else -> null
    }

    /** reads just the header off [stream] and classifies it. short files are fine, they come back null */
    fun kindOf(stream: InputStream): DocumentImportKind? = kindOf(readHeader(stream))

    fun readHeader(stream: InputStream): ByteArray {
        val buffer = ByteArray(HEADER_BYTES)
        var filled = 0
        // content providers are allowed to return short reads, keep going till we have the lot or hit EOF
        while (filled < buffer.size) {
            val n = stream.read(buffer, filled, buffer.size - filled)
            if (n <= 0) break
            filled += n
        }
        return buffer.copyOf(filled)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
