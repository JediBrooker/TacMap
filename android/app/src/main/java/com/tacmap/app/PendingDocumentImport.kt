package com.tacmap.app

import java.util.UUID

internal enum class DocumentImportKind(val savedValue: String, val mimeTypes: Array<String>) {
    SYMBOL_PACK("symbols", arrayOf("application/json", "application/octet-stream")),
    PDF("pdf", arrayOf("application/pdf")),
    GEO_JSON("geojson", arrayOf("application/geo+json", "application/json", "*/*")),
    MBTILES("mbtiles", arrayOf("*/*")),
    KML("kml", arrayOf(
        "application/vnd.google-earth.kml+xml",
        "application/vnd.google-earth.kmz",
        "*/*",
    ));

    companion object {
        fun fromSavedValue(value: String?): DocumentImportKind? =
            entries.firstOrNull { it.savedValue == value }
    }
}

internal data class PendingDocumentImport(
    val token: String,
    val kind: DocumentImportKind,
    val uri: String,
    val persistableGrantTaken: Boolean,
)

/**
 * Activity-owned exact-once handoff. A cancelled Compose coroutine abandons
 * its claim so a rebuilt MapScreen retries; completion clears it permanently.
 */
internal class PendingDocumentImportCoordinator(
    initial: PendingDocumentImport? = null,
) {
    private var pending: PendingDocumentImport? = initial
    private var claimedToken: String? = null

    @Synchronized
    fun current(): PendingDocumentImport? = pending

    @Synchronized
    fun publish(
        kind: DocumentImportKind,
        uri: String,
        persistableGrantTaken: Boolean,
        token: String = UUID.randomUUID().toString(),
    ): PendingDocumentImport {
        val result = PendingDocumentImport(token, kind, uri, persistableGrantTaken)
        pending = result
        claimedToken = null
        return result
    }

    @Synchronized
    fun claim(token: String): PendingDocumentImport? {
        val value = pending?.takeIf { it.token == token } ?: return null
        if (claimedToken != null) return null
        claimedToken = token
        return value
    }

    @Synchronized
    fun abandon(token: String): Boolean {
        if (claimedToken != token || pending?.token != token) return false
        claimedToken = null
        return true
    }

    @Synchronized
    fun complete(token: String): PendingDocumentImport? {
        if (pending?.token != token || claimedToken != token) return null
        val completed = pending
        pending = null
        claimedToken = null
        return completed
    }

    /** Process death always restores an in-flight claim as ready-to-retry. */
    @Synchronized
    fun savedSnapshot(): PendingDocumentImport? = pending
}
