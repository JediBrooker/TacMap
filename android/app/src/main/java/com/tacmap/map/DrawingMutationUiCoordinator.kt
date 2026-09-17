package com.tacmap.map

import com.tacmap.localization.L10n
import com.tacmap.localization.Messages
import com.tacmap.localization.LocalizedMessage

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
    data class Failed(val pendingMessage: LocalizedMessage) : DrawingMutationUiResult {
        val message: String get() = pendingMessage.text
    }

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
            DrawingMutationIntent.CREATE -> Messages.displayTheDrawingWasNotSavedYourDraftIsStillMessage()
            DrawingMutationIntent.EDIT -> Messages.displayTheDrawingChangeWasNotSavedThePreviousDrawingMessage()
            DrawingMutationIntent.DELETE -> Messages.displayTheDrawingWasNotDeletedItRemainsSelectedMessage()
            DrawingMutationIntent.VISIBILITY -> Messages.displayLayerVisibilityWasNotSavedThePreviousSettingRemainsMessage()
        }
        return DrawingMutationUiResult.Failed(
            Messages.displayUnlockMissionDataOrFreeDeviceStorageThenTapMessage("").withArgument(0, subject)
        )
    }
}
