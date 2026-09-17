package com.tacmap.localization

/** In-memory display state. Diagnostic details remain verbatim; never persist this as a wire format. */
data class LocalizedMessage internal constructor(
    val id: String?,
    val fallback: String,
    val arguments: List<String>,
    private val plural: Pair<String, Int>? = null,
    private val nestedArguments: Map<Int, LocalizedMessage> = emptyMap(),
) {
    val text: String get() = plural?.let { L10n.quantity(it.first, it.second) } ?: id?.let { L10n.message(it, fallback, *arguments.mapIndexed { index, value -> nestedArguments[index]?.text ?: value }.toTypedArray()) } ?: fallback

    fun withArgument(index: Int, message: LocalizedMessage): LocalizedMessage {
        require(index in arguments.indices)
        return copy(nestedArguments = nestedArguments + (index to message))
    }

    companion object {
        fun quantity(noun: String, count: Int): LocalizedMessage = LocalizedMessage("count.$noun", "", emptyList(), noun to count)
        fun literal(text: String): LocalizedMessage = LocalizedMessage(null, text, emptyList())
    }
}

/** Implemented by app-owned errors whose wording follows the display language. */
interface LocalizedMessageFailure {
    val localizedMessage: LocalizedMessage
}

val Throwable.displayMessage: LocalizedMessage
    get() = (this as? LocalizedMessageFailure)?.localizedMessage ?: LocalizedMessage.literal(message.orEmpty())
