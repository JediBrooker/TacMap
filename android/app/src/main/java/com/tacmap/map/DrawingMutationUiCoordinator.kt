package com.tacmap.map

/** Durable drawing operations exposed to UI surfaces. */
internal enum class DrawingMutationIntent {
    CREATE,
    EDIT,
    DELETE,
    VISIBILITY,
}

/**
 * Checked outcome used by production Compose callbacks. A failed outcome must
 * keep the initiating draft, editor, picker, or selection open so Retry can
 * repeat the exact same durable mutation.
 */
sealed interface DrawingMutationUiResult {
    data object Saved : DrawingMutationUiResult
    data class Failed(val message: String) : DrawingMutationUiResult

    val saved: Boolean get() = this === Saved
    val shouldCloseTransientUi: Boolean get() = saved
}

/** Single UI-facing boundary around Boolean-returning durable DrawingStore APIs. */
internal object DrawingMutationUiCoordinator {
    fun attempt(
        intent: DrawingMutationIntent,
        persist: () -> Boolean,
    ): DrawingMutationUiResult {
        val saved = runCatching(persist).getOrDefault(false)
        if (saved) return DrawingMutationUiResult.Saved
        val subject = when (intent) {
            DrawingMutationIntent.CREATE -> "The drawing was not saved. Your draft is still open."
            DrawingMutationIntent.EDIT -> "The drawing change was not saved. The previous drawing remains active."
            DrawingMutationIntent.DELETE -> "The drawing was not deleted. It remains selected."
            DrawingMutationIntent.VISIBILITY -> "Layer visibility was not saved. The previous setting remains active."
        }
        return DrawingMutationUiResult.Failed(
            "$subject Unlock mission data or free device storage, then tap Retry."
        )
    }
}
