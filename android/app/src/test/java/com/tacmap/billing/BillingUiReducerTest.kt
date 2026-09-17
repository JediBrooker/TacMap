package com.tacmap.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingUiReducerTest {
    @Test fun productStateMachineMovesFromConnectionToActionableReadyState() {
        var state = BillingUiState()
        state = BillingUiReducer.reduce(state, BillingUiEvent.Connecting)
        assertEquals(BillingPhase.Connecting, state.phase)
        assertFalse(state.purchaseEnabled)

        state = BillingUiReducer.reduce(state, BillingUiEvent.LoadingProduct)
        assertEquals(BillingPhase.LoadingProduct, state.phase)

        state = BillingUiReducer.reduce(state, BillingUiEvent.ProductLoaded("\$9.99"))
        assertEquals(BillingPhase.Ready, state.phase)
        assertEquals("\$9.99", state.priceText)
        assertTrue(state.purchaseEnabled)
        assertNull(state.message)
    }

    @Test fun failedProductLoadHasAnActionableRetryAndNoStalePurchaseButton() {
        val ready = BillingUiReducer.reduce(
            BillingUiState(),
            BillingUiEvent.ProductLoaded("\$9.99"),
        )

        val failed = BillingUiReducer.reduce(
            ready,
            BillingUiEvent.Error(
                message = "Could not load product",
                retryable = true,
                discardPrice = true,
            ),
        )

        assertEquals(BillingPhase.Error, failed.phase)
        assertTrue(failed.retryable)
        assertFalse(failed.purchaseEnabled)
        assertNull(failed.priceText)
    }

    @Test fun freshPaywallLoadClearsPreviouslyDisplayedPriceUntilPlayResponds() {
        val ready = BillingUiReducer.reduce(
            BillingUiState(),
            BillingUiEvent.ProductLoaded("\$9.99"),
        )

        val reopened = BillingUiReducer.reduce(ready, BillingUiEvent.LoadingProduct)

        assertEquals(BillingPhase.LoadingProduct, reopened.phase)
        assertNull(reopened.priceText)
        assertFalse(reopened.purchaseEnabled)
    }

    @Test fun productResultDoesNotHideRestoreProgress() {
        var state = BillingUiReducer.reduce(BillingUiState(), BillingUiEvent.Restoring)

        state = BillingUiReducer.reduce(state, BillingUiEvent.ProductLoaded("\$9.99"))

        assertEquals(BillingPhase.Restoring, state.phase)
        assertEquals("\$9.99", state.priceText)
        assertFalse(state.purchaseEnabled)
    }

    @Test fun paywallRemountConnectionAndProductLoadDoNotHidePurchaseProgress() {
        var state = BillingUiReducer.reduce(
            BillingUiState(priceText = "old price"),
            BillingUiEvent.Purchasing,
        )

        state = BillingUiReducer.reduce(state, BillingUiEvent.Connecting)
        assertEquals(BillingPhase.Purchasing, state.phase)
        assertNull(state.priceText)
        assertFalse(state.purchaseEnabled)

        state = BillingUiReducer.reduce(state, BillingUiEvent.LoadingProduct)
        assertEquals(BillingPhase.Purchasing, state.phase)
        assertFalse(state.purchaseEnabled)

        state = BillingUiReducer.reduce(state, BillingUiEvent.ProductLoaded("fresh price"))
        assertEquals(BillingPhase.Purchasing, state.phase)
        assertEquals("fresh price", state.priceText)
        assertFalse(state.purchaseEnabled)
    }

    @Test fun paywallRemountConnectionAndProductLoadDoNotHideRestoreProgress() {
        var state = BillingUiReducer.reduce(
            BillingUiState(priceText = "old price"),
            BillingUiEvent.Restoring,
        )

        state = BillingUiReducer.reduce(state, BillingUiEvent.Connecting)
        assertEquals(BillingPhase.Restoring, state.phase)
        assertNull(state.priceText)
        assertFalse(state.purchaseEnabled)

        state = BillingUiReducer.reduce(state, BillingUiEvent.LoadingProduct)
        assertEquals(BillingPhase.Restoring, state.phase)

        state = BillingUiReducer.reduce(state, BillingUiEvent.ProductLoaded("fresh price"))
        assertEquals(BillingPhase.Restoring, state.phase)
        assertEquals("fresh price", state.priceText)
        assertFalse(state.purchaseEnabled)
    }

    @Test fun pendingPurchaseNeverEnablesPurchaseOrReportsReady() {
        val ready = BillingUiReducer.reduce(
            BillingUiState(),
            BillingUiEvent.ProductLoaded("\$9.99"),
        )

        val pending = BillingUiReducer.reduce(
            ready,
            BillingUiEvent.Pending("Payment pending"),
        )

        assertEquals(BillingPhase.Pending, pending.phase)
        assertFalse(pending.purchaseEnabled)
        assertEquals("Payment pending", pending.message)

        val connectionAttempt = BillingUiReducer.reduce(pending, BillingUiEvent.Connecting)
        val productLoad = BillingUiReducer.reduce(connectionAttempt, BillingUiEvent.LoadingProduct)
        val withPrice = BillingUiReducer.reduce(productLoad, BillingUiEvent.ProductLoaded("\$9.99"))
        assertEquals(BillingPhase.Pending, withPrice.phase)
        assertEquals("\$9.99", withPrice.priceText)
        assertFalse(withPrice.purchaseEnabled)

        val cleared = BillingUiReducer.reduce(withPrice, BillingUiEvent.PendingCleared)
        assertEquals(BillingPhase.Ready, cleared.phase)
        assertTrue(cleared.purchaseEnabled)
    }

    @Test fun cancellationFeedbackCanKeepTheKnownProductPurchasable() {
        val ready = BillingUiReducer.reduce(
            BillingUiState(),
            BillingUiEvent.ProductLoaded("\$9.99"),
        )

        val cancelled = BillingUiReducer.reduce(
            ready,
            BillingUiEvent.Error(
                message = "Purchase cancelled",
                retryable = false,
                allowPurchase = true,
            ),
        )

        assertEquals(BillingPhase.Error, cancelled.phase)
        assertTrue(cancelled.purchaseEnabled)
        assertFalse(cancelled.retryable)

        // Re-composing the paywall after the Play Activity returns must not
        // immediately erase cancellation/error feedback with the cached price.
        val remounted = BillingUiReducer.reduce(
            cancelled,
            BillingUiEvent.ProductLoaded("\$9.99"),
        )
        assertEquals(BillingPhase.Error, remounted.phase)
        assertEquals("Purchase cancelled", remounted.message)
    }
}
