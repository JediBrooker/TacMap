package com.tacmap.localization

/** In-memory display state. Diagnostic details remain verbatim; never persist this as a wire format. */
data class LocalizedMessage internal constructor(
    val id: String?,
    val fallback: String,
    val arguments: List<String>,
    private val nestedArguments: Map<Int, LocalizedMessage> = emptyMap(),
) {
    val text: String get() = id?.let { L10n.message(it, fallback, *arguments.mapIndexed { index, value -> nestedArguments[index]?.text ?: value }.toTypedArray()) } ?: fallback

    fun withArgument(index: Int, message: LocalizedMessage): LocalizedMessage {
        require(index in arguments.indices)
        return copy(nestedArguments = nestedArguments + (index to message))
    }

    companion object {
        fun literal(text: String): LocalizedMessage = LocalizedMessage(null, text, emptyList())
    }
}
