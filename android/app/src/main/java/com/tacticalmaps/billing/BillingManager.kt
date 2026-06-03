package com.tacticalmaps.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wraps Google Play Billing for the single one-time, non-consumable
 * "unlock_full" product that permanently unlocks the app after the trial.
 *
 * Exposes [isPurchased] (the entitlement) and [priceText] (the store's
 * localized price, e.g. "$5.00") as flows for the paywall UI. Call [start]
 * once, [launchPurchase] from the paywall button, and [restore] from
 * "Restore purchase".
 */
class BillingManager(context: Context) : PurchasesUpdatedListener, BillingClientStateListener {

    private val _isPurchased = MutableStateFlow(false)
    val isPurchased: StateFlow<Boolean> = _isPurchased.asStateFlow()

    private val _priceText = MutableStateFlow<String?>(null)
    val priceText: StateFlow<String?> = _priceText.asStateFlow()

    private var productDetails: ProductDetails? = null

    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    fun start() {
        if (client.connectionState == BillingClient.ConnectionState.CONNECTED) {
            queryProduct(); restore()
        } else {
            client.startConnection(this)
        }
    }

    override fun onBillingSetupFinished(result: BillingResult) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            queryProduct()
            restore()
        }
    }

    override fun onBillingServiceDisconnected() {
        // BillingClient is single-use per connection; the next start() reconnects.
    }

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        client.queryProductDetailsAsync(params) { result, productDetailsList ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                productDetails = productDetailsList.firstOrNull()
                _priceText.value =
                    productDetails?.oneTimePurchaseOfferDetails?.formattedPrice
            }
        }
    }

    /** Re-check Play for an existing entitlement ("Restore purchase"). */
    fun restore() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { _, purchases ->
            purchases.forEach { handlePurchase(it) }
        }
    }

    fun launchPurchase(activity: Activity) {
        val details = productDetails ?: return
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()
        client.launchBillingFlow(activity, params)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            purchases.forEach { handlePurchase(it) }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (!purchase.products.contains(PRODUCT_ID)) return
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return

        _isPurchased.value = true

        // Acknowledge within Play's 3-day window or the purchase is refunded.
        if (!purchase.isAcknowledged) {
            val ack = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            client.acknowledgePurchase(ack) { /* entitlement already granted locally */ }
        }
    }

    companion object {
        /** Must match the in-app product ID created in Play Console. */
        const val PRODUCT_ID = "unlock_full"
    }
}
