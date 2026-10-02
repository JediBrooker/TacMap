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

/** the bits of SharedPreferences the one-time legacy read needs, so it's testable on the JVM */
internal interface LegacyPendingImportPrefs {
    fun getString(key: String): String?
    fun getBoolean(key: String): Boolean
    /** wipe the whole file, true once it's durably gone */
    fun clearAll(): Boolean
}

/**
 * D5-18: the picked document's URI (which carries the file name) never goes into
 * plaintext prefs any more. It lives in memory + savedInstanceState only. Older
 * builds left a record in pending_document_import_v1, that gets read ONCE, adopted
 * into memory and wiped straight away.
 */
internal object PendingDocumentImportRestore {
    const val LEGACY_PREFS = "pending_document_import_v1"
    const val KEY_TOKEN = "pending_import_token"
    const val KEY_KIND = "pending_import_kind"
    const val KEY_URI = "pending_import_uri"
    const val KEY_GRANT = "pending_import_grant"

    fun fromValues(token: String?, kind: String?, uri: String?, grant: Boolean): PendingDocumentImport? {
        if (token.isNullOrBlank() || uri.isNullOrBlank()) return null
        val parsedKind = DocumentImportKind.fromSavedValue(kind) ?: return null
        return PendingDocumentImport(token, parsedKind, uri, grant)
    }

    /**
     * savedInstanceState wins. The legacy file is wiped either way (even when the
     * bundle already has the import) so the URI doesn't hang around on disk.
     */
    fun restore(fromBundle: PendingDocumentImport?, legacy: LegacyPendingImportPrefs?): PendingDocumentImport? {
        val old = legacy?.let {
            fromValues(it.getString(KEY_TOKEN), it.getString(KEY_KIND), it.getString(KEY_URI), it.getBoolean(KEY_GRANT))
        }
        if (legacy != null && (old != null || legacy.getString(KEY_URI) != null || legacy.getString(KEY_TOKEN) != null)) {
            legacy.clearAll()
        }
        return fromBundle ?: old
    }

    /**
     * Persisted read grants the app is still holding. Imports are the only thing that
     * ever takes one, so anything not backing the import in hand is a leak from a
     * pick that never finished (process died before onSaveInstanceState etc).
     */
    fun orphanedGrants(persisted: List<String>, pending: PendingDocumentImport?): List<String> =
        persisted.filter { it != pending?.uri }
}
