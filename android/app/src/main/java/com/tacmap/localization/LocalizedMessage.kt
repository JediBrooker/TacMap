package com.tacmap.localization

/** In-memory display state. Diagnostic details remain verbatim; never persist this as a wire format. */
data class LocalizedMessage internal constructor(
    val id: String?,
    val fallback: String,
    val arguments: List<String>,
) {
    val text: String get() = id?.let { L10n.message(it, fallback, *arguments.toTypedArray()) } ?: fallback

    companion object {
        fun literal(text: String): LocalizedMessage = LocalizedMessage(null, text, emptyList())
    }
}
