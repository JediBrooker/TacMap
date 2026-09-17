package com.tacmap.billing

import com.tacmap.localization.Messages
import com.tacmap.localization.LocalizedMessage

/** One paywall contract shared by the expired-trial and in-trial entry points. */
data class BillingUiState(
    val phase: BillingPhase = BillingPhase.Idle,
    val priceText: String? = null,
    val pendingMessage: LocalizedMessage? = null,
    val retryable: Boolean = false,
    val purchaseEnabled: Boolean = false,
) {
    val message: String? get() = pendingMessage?.text
}

enum class BillingPhase {
    Idle,
    Connecting,
    LoadingProduct,
    Ready,
    Restoring,
    Purchasing,
    Pending,
    Error,
}

internal sealed interface BillingUiEvent {
    data object Connecting : BillingUiEvent
    data object LoadingProduct : BillingUiEvent
    data class ProductLoaded(val priceText: String) : BillingUiEvent
    data object Restoring : BillingUiEvent
    data object Purchasing : BillingUiEvent
    data class Pending(val message: LocalizedMessage) : BillingUiEvent
    data object PendingCleared : BillingUiEvent
    data class Error(
        val message: LocalizedMessage,
        val retryable: Boolean,
        val allowPurchase: Boolean = false,
        val discardPrice: Boolean = false,
    ) : BillingUiEvent
}

internal object BillingUiReducer {
    fun reduce(state: BillingUiState, event: BillingUiEvent): BillingUiState = when (event) {
        BillingUiEvent.Connecting -> if (
            state.phase == BillingPhase.Pending ||
            state.phase == BillingPhase.Purchasing ||
            state.phase == BillingPhase.Restoring
        ) {
            state.copy(priceText = null, purchaseEnabled = false)
        } else {
            state.copy(
                phase = BillingPhase.Connecting,
                priceText = null,
                pendingMessage = Messages.billingConnectingToGooglePlayMessage(),
                retryable = false,
                purchaseEnabled = false,
            )
        }

        BillingUiEvent.LoadingProduct -> if (
            state.phase == BillingPhase.Pending ||
            state.phase == BillingPhase.Purchasing ||
            state.phase == BillingPhase.Restoring
        ) {
            state.copy(priceText = null, purchaseEnabled = false)
        } else {
            state.copy(
                phase = BillingPhase.LoadingProduct,
                priceText = null,
                pendingMessage = Messages.billingLoadingPriceFromGooglePlayMessage(),
                retryable = false,
                purchaseEnabled = false,
            )
        }

        is BillingUiEvent.ProductLoaded -> if (
            state.phase == BillingPhase.Restoring ||
            state.phase == BillingPhase.Purchasing ||
            state.phase == BillingPhase.Pending ||
            (state.phase == BillingPhase.Error && state.priceText != null)
        ) {
            state.copy(priceText = event.priceText)
        } else {
            BillingUiState(
                phase = BillingPhase.Ready,
                priceText = event.priceText,
                purchaseEnabled = true,
            )
        }

        BillingUiEvent.Restoring -> state.copy(
            phase = BillingPhase.Restoring,
            pendingMessage = Messages.billingCheckingGooglePlayForYourPurchaseMessage(),
            retryable = false,
            purchaseEnabled = false,
        )

        BillingUiEvent.Purchasing -> state.copy(
            phase = BillingPhase.Purchasing,
            pendingMessage = Messages.billingOpeningGooglePlayMessage(),
            retryable = false,
            purchaseEnabled = false,
        )

        is BillingUiEvent.Pending -> state.copy(
            phase = BillingPhase.Pending,
            pendingMessage = event.message,
            retryable = false,
            purchaseEnabled = false,
        )

        BillingUiEvent.PendingCleared -> if (state.phase != BillingPhase.Pending) {
            state
        } else if (state.priceText != null) {
            BillingUiState(
                phase = BillingPhase.Ready,
                priceText = state.priceText,
                purchaseEnabled = true,
            )
        } else {
            BillingUiState()
        }

        is BillingUiEvent.Error -> {
            val price = if (event.discardPrice) null else state.priceText
            state.copy(
                phase = BillingPhase.Error,
                priceText = price,
                pendingMessage = event.message,
                retryable = event.retryable,
                purchaseEnabled = event.allowPurchase && price != null,
            )
        }
    }
}
