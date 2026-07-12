package com.tacmap.billing

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
import java.lang.ref.WeakReference

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

    private val prefs = context.applicationContext.getSharedPreferences("billing_entitlement", Context.MODE_PRIVATE)

    private val _isPurchased = MutableStateFlow(cachedEntitlementIsValid())
    val isPurchased: StateFlow<Boolean> = _isPurchased.asStateFlow()

    private val _priceText = MutableStateFlow<String?>(null)
    val priceText: StateFlow<String?> = _priceText.asStateFlow()

    private var productDetails: ProductDetails? = null
    private val whenConnected = mutableListOf<() -> Unit>()

    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    fun start() {
        connect { queryProduct() }
    }

    fun end() {
        synchronized(whenConnected) { whenConnected.clear() }
        client.endConnection()
    }

    /** Local-only expiry check; foregrounding the app never contacts Play. */
    fun refreshLocalEntitlement() {
        _isPurchased.value = cachedEntitlementIsValid()
    }

    override fun onBillingSetupFinished(result: BillingResult) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            val actions = synchronized(whenConnected) { whenConnected.toList().also { whenConnected.clear() } }
            actions.forEach { it() }
        } else {
            synchronized(whenConnected) { whenConnected.clear() }
        }
    }

    override fun onBillingServiceDisconnected() {
        // BillingClient is single-use per connection; the next start() reconnects.
    }

    private fun queryProduct(after: (() -> Unit)? = null) {
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
                after?.invoke()
            }
        }
    }

    /** Re-check Play for an existing entitlement ("Restore purchase"). */
    fun restore() {
        connect { queryPurchases() }
    }

    private fun queryPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            val activePurchases = purchases.filter(::isEntitlingPurchase)
            _isPurchased.value = activePurchases.isNotEmpty()
            prefs.edit()
                .putBoolean(KEY_CACHED_ENTITLEMENT, activePurchases.isNotEmpty())
                .putLong(KEY_LAST_VERIFIED_MS, System.currentTimeMillis())
                .apply()
            activePurchases.forEach(::acknowledgeIfNeeded)
        }
    }

    fun launchPurchase(activity: Activity) {
        val activityRef = WeakReference(activity)
        connect {
            val launch: () -> Unit = {
                val current = activityRef.get()
                if (current != null && !current.isFinishing && !current.isDestroyed) {
                    productDetails?.let { launchPurchaseWithDetails(current, it) }
                }
                Unit
            }
            if (productDetails == null) queryProduct(launch) else launch()
        }
    }

    private fun launchPurchaseWithDetails(activity: Activity, details: ProductDetails) {
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
        Unit
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            purchases.forEach { handlePurchase(it) }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (!isEntitlingPurchase(purchase)) return

        _isPurchased.value = true
        prefs.edit()
            .putBoolean(KEY_CACHED_ENTITLEMENT, true)
            .putLong(KEY_LAST_VERIFIED_MS, System.currentTimeMillis())
            .apply()
        acknowledgeIfNeeded(purchase)
    }

    /** Billing is deliberately cold until a purchase/restore/paywall action. */
    private fun connect(action: () -> Unit) {
        when (client.connectionState) {
            BillingClient.ConnectionState.CONNECTED -> action()
            BillingClient.ConnectionState.CONNECTING -> synchronized(whenConnected) { whenConnected += action }
            else -> {
                synchronized(whenConnected) { whenConnected += action }
                client.startConnection(this)
            }
        }
    }

    private fun isEntitlingPurchase(purchase: Purchase): Boolean =
        purchase.products.contains(PRODUCT_ID) &&
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED

    private fun acknowledgeIfNeeded(purchase: Purchase) {
        // Acknowledge within Play's 3-day window or the purchase is refunded.
        if (!purchase.isAcknowledged) {
            val ack = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            client.acknowledgePurchase(ack) { /* entitlement already granted locally */ }
        }
    }

    private fun cachedEntitlementIsValid(now: Long = System.currentTimeMillis()): Boolean {
        if (!prefs.getBoolean(KEY_CACHED_ENTITLEMENT, false)) return false
        val checked = prefs.getLong(KEY_LAST_VERIFIED_MS, 0L)
        return checked > 0L && now >= checked && now - checked <= ENTITLEMENT_VALIDITY_MS
    }

    companion object {
        /** Must match the in-app product ID created in Play Console. */
        const val PRODUCT_ID = "unlock_full"
        private const val KEY_CACHED_ENTITLEMENT = "cached_entitlement_v1"
        private const val KEY_LAST_VERIFIED_MS = "last_verified_ms_v1"
        private const val ENTITLEMENT_VALIDITY_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
