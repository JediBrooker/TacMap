package com.tacmap.billing

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * Google Play Billing coordinator for the permanent `unlock_full` product.
 *
 * Ownership observation starts with the Activity and refreshes on throttled
 * foreground transitions. Product/price loading remains scoped to a visible
 * paywall. A previously verified purchase is durable: only a successful,
 * authoritative ownership query can remove it.
 */
class BillingManager(context: Context) : PurchasesUpdatedListener, BillingClientStateListener {
    private val appContext = context.applicationContext
    private val entitlement = BillingEntitlementRepository(
        SharedPreferencesBillingPersistence(
            appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        )
    )

    private val _isPurchased = MutableStateFlow(entitlement.snapshot().isPurchased)
    val isPurchased: StateFlow<Boolean> = _isPurchased.asStateFlow()

    private val _uiState = MutableStateFlow(BillingUiState())
    val uiState: StateFlow<BillingUiState> = _uiState.asStateFlow()

    private val _storeIssue = MutableStateFlow<BillingStoreIssue?>(null)
    val storeIssue: StateFlow<BillingStoreIssue?> = _storeIssue.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val productLoadPolicy = BillingProductLoadPolicy()
    private val entitlementOperations = BillingEntitlementOperationPolicy()
    private var productDetails: ProductDetails? = null
    private var deferredProductFailure: String? = null
    private var lastEntitlementQueryStartedElapsedMs: Long? = null
    private var observationStarted = false
    private var ended = false
    private var retryAction = UiRetryAction.None
    private var connectionAttemptInFlight = false
    private var connectionGeneration = 0L
    private var setupWatchdog: Runnable? = null
    private val whenConnected = mutableListOf<ConnectedAction>()
    private val activeProductQueries = mutableSetOf<Long>()
    private val productWatchdogs = mutableMapOf<Long, Runnable>()
    private val activeEntitlementQueries = mutableMapOf<Long, ActiveEntitlementQuery>()
    private val entitlementWatchdogs = mutableMapOf<Long, Runnable>()
    private val entitlementRetryTasks = mutableMapOf<Long, Runnable>()
    private val acknowledgementAttempts = BillingAttemptGate<String>()
    private val activeAcknowledgements = mutableMapOf<String, ActiveAcknowledgement>()
    private val acknowledgementWatchdogs = mutableMapOf<String, Runnable>()
    private val acknowledgementRetryTasks = mutableMapOf<String, Runnable>()

    private val client = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .enableAutoServiceReconnection()
        .build()

    /** Start purchase observation without loading a product or price. */
    fun start() {
        if (observationStarted || ended) return
        observationStarted = true
        beginEntitlementRefresh(force = true, userInitiated = false)
        retryStoredAcknowledgements()
    }

    /** Refresh at most every 15 minutes of monotonic foreground time. */
    fun onAppForeground() {
        if (ended) return
        if (!observationStarted) {
            start()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (
            BillingForegroundRefreshPolicy.shouldRefresh(
                lastStartedElapsedMs = lastEntitlementQueryStartedElapsedMs,
                nowElapsedMs = now,
                queryInFlight = hasCurrentEntitlementQuery(),
            )
        ) {
            beginEntitlementRefresh(force = false, userInitiated = false)
        }
        retryStoredAcknowledgements()
    }

    /** Called by either paywall when it becomes visible. */
    fun loadProduct() {
        if (ended) return
        val generation = productLoadPolicy.beginPaywallLoad()
        cancelTrackedProductQueries()
        // ProductDetails can become stale while a paywall is closed. Every
        // appearance/retry gets a fresh Play response before purchase is enabled.
        productDetails = null
        deferredProductFailure = null
        retryAction = UiRetryAction.None
        if (client.connectionState != BillingClient.ConnectionState.CONNECTED) {
            transition(BillingUiEvent.Connecting)
        }
        connect(
            action = {
                if (productLoadPolicy.accepts(generation)) {
                    transition(BillingUiEvent.LoadingProduct)
                    queryProduct(generation)
                }
            },
            onFailure = { result ->
                if (productLoadPolicy.accepts(generation)) {
                    failProductLoad(connectionFailureMessage(result), retryable = true)
                }
            },
        )
    }

    fun retry() {
        val action = retryAction
        retryAction = UiRetryAction.None
        when (action) {
            UiRetryAction.None -> loadProduct()
            UiRetryAction.LoadProduct -> loadProduct()
            UiRetryAction.Restore -> restore()
        }
    }

    /** Re-check Play immediately for a previous purchase. */
    fun restore() {
        if (ended) return
        retryAction = UiRetryAction.None
        transition(BillingUiEvent.Restoring)
        beginEntitlementRefresh(force = true, userInitiated = true)
    }

    fun retryStoreIssue() {
        val issue = _storeIssue.value
        _storeIssue.value = null
        when (issue?.kind) {
            BillingStoreIssueKind.EntitlementPersistence ->
                beginEntitlementRefresh(force = true, userInitiated = false)
            BillingStoreIssueKind.PurchaseAcknowledgement ->
                retryStoredAcknowledgements(forceNewGeneration = true)
            null -> Unit
        }
    }

    fun dismissStoreIssue() {
        _storeIssue.value = null
    }

    fun launchPurchase(activity: Activity) {
        if (ended) return
        val details = productDetails
        if (details == null) {
            failProductLoad("The Google Play price is not ready yet. Retry to load it.", retryable = true)
            return
        }

        transition(BillingUiEvent.Purchasing)
        val activityRef = WeakReference(activity)
        connect(
            action = {
                val current = activityRef.get()
                if (current == null || current.isFinishing || current.isDestroyed) {
                    showError(
                        "The purchase window could not open. Return to TacMap and try Unlock again.",
                        retryable = false,
                        allowPurchase = true,
                    )
                } else {
                    launchPurchaseWithDetails(current, details)
                }
            },
            onFailure = { result ->
                recoverFromFailedLaunch(connectionFailureMessage(result))
            },
        )
    }

    fun end() {
        if (ended) return
        ended = true
        handler.removeCallbacksAndMessages(null)
        synchronized(whenConnected) { whenConnected.clear() }
        connectionAttemptInFlight = false
        setupWatchdog = null
        activeProductQueries.clear()
        productWatchdogs.clear()
        activeEntitlementQueries.clear()
        entitlementWatchdogs.clear()
        entitlementRetryTasks.clear()
        acknowledgementAttempts.clear()
        activeAcknowledgements.clear()
        acknowledgementWatchdogs.clear()
        acknowledgementRetryTasks.clear()
        client.endConnection()
    }

    override fun onBillingSetupFinished(result: BillingResult) {
        cancelSetupWatchdog()
        connectionAttemptInFlight = false
        val actions = synchronized(whenConnected) {
            whenConnected.toList().also { whenConnected.clear() }
        }
        if (ended) return
        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            actions.forEach { it.action() }
        } else {
            actions.forEach { it.onFailure(result) }
        }
    }

    override fun onBillingServiceDisconnected() {
        // Auto-service reconnection is enabled, but Play does not guarantee an
        // operation callback after this signal. Explicitly release every local
        // operation so the UI cannot remain wedged behind an in-flight flag.
        cancelSetupWatchdog()
        connectionAttemptInFlight = false
        val result = localFailureResult(
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
            "Google Play disconnected before the operation completed.",
        )
        val actions = synchronized(whenConnected) {
            whenConnected.toList().also { whenConnected.clear() }
        }
        actions.forEach { it.onFailure(result) }
        failActiveOperationsAfterDisconnect(result)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (ended) return
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> handlePurchaseUpdate(purchases.orEmpty())

            BillingClient.BillingResponseCode.USER_CANCELED -> showError(
                "Purchase cancelled. You have not been charged.",
                retryable = false,
                allowPurchase = true,
            )

            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restore()

            else -> {
                val transient = isTransient(result.responseCode)
                retryAction = if (transient) UiRetryAction.Restore else UiRetryAction.None
                showError(
                    if (transient) {
                        "Google Play could not confirm the purchase. Retry to check your ownership before purchasing again."
                    } else {
                        purchaseFailureMessage(result)
                    },
                    retryable = transient,
                    allowPurchase = !transient,
                )
            }
        }
    }

    private fun queryProduct(generation: Long) {
        if (ended || !productLoadPolicy.accepts(generation)) return
        activeProductQueries += generation
        scheduleProductWatchdog(generation)
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
        client.queryProductDetailsAsync(params) { result, queryResult ->
            if (!completeProductQuery(generation) || ended || !productLoadPolicy.accepts(generation)) {
                return@queryProductDetailsAsync
            }
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                failProductLoad(productFailureMessage(result), retryable = true)
                return@queryProductDetailsAsync
            }

            val details = queryResult.productDetailsList.firstOrNull { it.productId == PRODUCT_ID }
            val price = details?.oneTimePurchaseOfferDetails?.formattedPrice
            when {
                details != null && price != null -> {
                    productDetails = details
                    deferredProductFailure = null
                    retryAction = UiRetryAction.None
                    transition(BillingUiEvent.ProductLoaded(price))
                }

                queryResult.unfetchedProductList.isNotEmpty() -> failProductLoad(
                    "Google Play could not return the TacMap unlock product. Check your connection and retry.",
                    retryable = true,
                )

                else -> failProductLoad(
                    "The TacMap unlock product is not currently available from Google Play. Retry, or check that you are using the Play Store build.",
                    retryable = true,
                )
            }
        }
    }

    private fun beginEntitlementRefresh(force: Boolean, userInitiated: Boolean) {
        if (ended || (!force && hasCurrentEntitlementQuery())) return
        val operationId = entitlementOperations.beginQuery()
        cancelTrackedEntitlementQueries()
        requestEntitlementRefresh(
            force = force,
            active = ActiveEntitlementQuery(
                operationId = operationId,
                userInitiated = userInitiated,
                completedRetries = 0,
            ),
        )
    }

    private fun requestEntitlementRefresh(
        force: Boolean,
        active: ActiveEntitlementQuery,
    ) {
        if (ended || !entitlementOperations.accepts(active.operationId)) return
        val now = SystemClock.elapsedRealtime()
        if (
            !force && !BillingForegroundRefreshPolicy.shouldRefresh(
                lastStartedElapsedMs = lastEntitlementQueryStartedElapsedMs,
                nowElapsedMs = now,
                queryInFlight = false,
            )
        ) return

        activeEntitlementQueries[active.operationId] = active
        lastEntitlementQueryStartedElapsedMs = now
        connect(
            action = {
                if (
                    activeEntitlementQueries[active.operationId] == active &&
                    entitlementOperations.accepts(active.operationId)
                ) {
                    queryPurchases(active)
                }
            },
            onFailure = { result ->
                completeEntitlementQuery(active)?.let {
                    handleEntitlementFailure(result, it)
                }
            },
        )
    }

    private fun queryPurchases(active: ActiveEntitlementQuery) {
        if (
            activeEntitlementQueries[active.operationId] != active ||
            !entitlementOperations.accepts(active.operationId)
        ) return
        scheduleEntitlementWatchdog(active)
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            val completed = completeEntitlementQuery(active) ?: return@queryPurchasesAsync
            if (ended || !entitlementOperations.accepts(completed.operationId)) {
                return@queryPurchasesAsync
            }
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                handleEntitlementFailure(result, completed)
                return@queryPurchasesAsync
            }

            val relevant = purchases.filter { it.products.contains(PRODUCT_ID) }
            val purchased = relevant.filter {
                it.purchaseState == Purchase.PurchaseState.PURCHASED
            }
            val pending = relevant.filter {
                it.purchaseState == Purchase.PurchaseState.PENDING
            }
            val purchasedTokens = purchased.mapTo(mutableSetOf()) { it.purchaseToken }
            val unacknowledgedTokens = purchased
                .filterNot { it.isAcknowledged }
                .mapTo(mutableSetOf()) { it.purchaseToken }

            if (!entitlement.recordAuthoritativeQuery(purchasedTokens, unacknowledgedTokens)) {
                handleLocalPersistenceFailure(completed)
                return@queryPurchasesAsync
            }

            val snapshot = entitlement.snapshot()
            _isPurchased.value = snapshot.isPurchased
            cancelAcknowledgementTrackingNotIn(snapshot.pendingAcknowledgements)
            clearResolvedStoreIssue(snapshot)
            purchased.filterNot { it.isAcknowledged }.forEach {
                startAcknowledgement(it.purchaseToken)
            }

            if (snapshot.isPurchased) {
                retryAction = UiRetryAction.None
            } else if (pending.isNotEmpty()) {
                transition(
                    BillingUiEvent.Pending(
                        "Your Google Play payment is pending. TacMap unlocks after Play confirms it."
                    )
                )
            } else if (completed.userInitiated) {
                val hasPrice = _uiState.value.priceText != null
                val deferredFailure = deferredProductFailure
                deferredProductFailure = null
                retryAction = if (hasPrice) UiRetryAction.None else UiRetryAction.LoadProduct
                showError(
                    if (hasPrice) {
                        "No active TacMap purchase was found on this Google Play account."
                    } else {
                        deferredFailure
                            ?: "No active purchase was found. Retry to load the unlock product."
                    },
                    retryable = !hasPrice,
                    allowPurchase = hasPrice,
                )
            } else {
                val deferredFailure = deferredProductFailure
                deferredProductFailure = null
                if (_uiState.value.phase == BillingPhase.Pending && deferredFailure != null) {
                    retryAction = UiRetryAction.LoadProduct
                    showError(
                        message = deferredFailure,
                        retryable = true,
                        allowPurchase = false,
                        discardPrice = true,
                    )
                } else {
                    transition(BillingUiEvent.PendingCleared)
                }
            }
        }
    }

    private fun handleEntitlementFailure(
        result: BillingResult,
        active: ActiveEntitlementQuery,
    ) {
        if (ended || !entitlementOperations.accepts(active.operationId)) return
        val transient = isTransient(result.responseCode)
        if (BillingRetryPolicy.shouldRetry(transient, active.completedRetries)) {
            scheduleEntitlementRetry(active)
            return
        }

        if (active.userInitiated) {
            retryAction = UiRetryAction.Restore
            showError(
                "Google Play could not check your purchase. Your known-good access has not been changed. Retry when Play is available.",
                retryable = true,
                allowPurchase = false,
            )
        }
    }

    private fun handleLocalPersistenceFailure(
        active: ActiveEntitlementQuery,
    ) {
        if (ended || !entitlementOperations.accepts(active.operationId)) return
        if (BillingRetryPolicy.shouldRetry(isTransient = true, active.completedRetries)) {
            scheduleEntitlementRetry(active)
            return
        }

        _storeIssue.value = BillingStoreIssues.entitlementPersistence()
        if (active.userInitiated) {
            retryAction = UiRetryAction.Restore
            showError(
                "TacMap could not safely save the restored entitlement. Free some device storage and retry.",
                retryable = true,
                allowPurchase = false,
            )
        }
    }

    private fun scheduleEntitlementRetry(active: ActiveEntitlementQuery) {
        if (entitlementRetryTasks.containsKey(active.operationId)) return
        val retry = active.copy(completedRetries = active.completedRetries + 1)
        lateinit var task: Runnable
        task = Runnable {
            if (entitlementRetryTasks[active.operationId] !== task) return@Runnable
            entitlementRetryTasks.remove(active.operationId)
            if (!ended && entitlementOperations.accepts(active.operationId)) {
                requestEntitlementRefresh(force = true, active = retry)
            }
        }
        entitlementRetryTasks[active.operationId] = task
        handler.postDelayed(task, BillingRetryPolicy.delayMs(active.completedRetries))
    }

    private fun handlePurchaseUpdate(purchases: List<Purchase>) {
        val relevant = purchases.filter { it.products.contains(PRODUCT_ID) }
        if (relevant.isEmpty()) {
            retryAction = UiRetryAction.Restore
            showError(
                "Google Play did not return a TacMap purchase. Retry to check your ownership before purchasing again.",
                retryable = true,
                allowPurchase = false,
            )
            return
        }

        var granted = false
        var pending = false
        relevant.forEach { purchase ->
            when (purchase.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> granted = handlePurchased(purchase) || granted
                Purchase.PurchaseState.PENDING -> pending = true
                else -> Unit
            }
        }
        if (!granted && pending) {
            transition(
                BillingUiEvent.Pending(
                    "Your Google Play payment is pending. TacMap unlocks after Play confirms it."
                )
            )
        } else if (!granted) {
            retryAction = UiRetryAction.Restore
            showError(
                "Google Play has not completed this purchase. Retry to check its status.",
                retryable = true,
                allowPurchase = false,
            )
        }
    }

    private fun handlePurchased(purchase: Purchase): Boolean {
        // A purchase callback is newer evidence than every ownership query
        // already in flight. In particular, an older OK-empty result must not
        // revoke the grant that is about to be durably recorded here.
        entitlementOperations.supersedeWithPurchase()
        cancelTrackedEntitlementQueries()
        val stored = entitlement.recordPurchase(
            purchaseToken = purchase.purchaseToken,
            needsAcknowledgement = !purchase.isAcknowledged,
        )
        if (!stored) {
            _storeIssue.value = BillingStoreIssues.entitlementPersistence()
            retryAction = UiRetryAction.Restore
            showError(
                "TacMap could not safely save the purchase. Do not purchase again; free some device storage, then retry Restore purchase.",
                retryable = true,
                allowPurchase = false,
            )
            return false
        }

        // Durable grant publication happens before acknowledgement.
        _isPurchased.value = true
        if (_storeIssue.value?.kind == BillingStoreIssueKind.EntitlementPersistence) {
            _storeIssue.value = null
        }
        if (!purchase.isAcknowledged) {
            startAcknowledgement(purchase.purchaseToken, forceNewGeneration = true)
        }
        return true
    }

    private fun startAcknowledgement(
        purchaseToken: String,
        forceNewGeneration: Boolean = false,
    ) {
        if (ended || purchaseToken !in entitlement.snapshot().pendingAcknowledgements) {
            cancelAcknowledgementTracking(purchaseToken, removeGeneration = true)
            return
        }
        val attempt = if (forceNewGeneration) {
            cancelAcknowledgementTracking(purchaseToken, removeGeneration = false)
            acknowledgementAttempts.begin(purchaseToken)
        } else {
            acknowledgementAttempts.beginIfIdle(purchaseToken) ?: return
        }
        requestAcknowledgement(purchaseToken, attempt)
    }

    private fun requestAcknowledgement(purchaseToken: String, attempt: BillingAttempt) {
        if (
            ended ||
            !acknowledgementAttempts.accepts(purchaseToken, attempt) ||
            purchaseToken !in entitlement.snapshot().pendingAcknowledgements ||
            purchaseToken in activeAcknowledgements
        ) return

        val active = ActiveAcknowledgement(purchaseToken, attempt)
        activeAcknowledgements[purchaseToken] = active
        connect(
            action = {
                if (activeAcknowledgements[purchaseToken] != active) return@connect
                val params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchaseToken)
                    .build()
                scheduleAcknowledgementWatchdog(active)
                client.acknowledgePurchase(params) { result ->
                    if (!completeAcknowledgement(active) || ended) {
                        return@acknowledgePurchase
                    }
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        if (!entitlement.recordAcknowledged(purchaseToken)) {
                            // Play has acknowledged it; an authoritative query
                            // can now observe that and clear the local work item.
                            cancelAcknowledgementTracking(
                                purchaseToken,
                                removeGeneration = true,
                            )
                            beginEntitlementRefresh(force = true, userInitiated = false)
                        } else {
                            cancelAcknowledgementTracking(
                                purchaseToken,
                                removeGeneration = true,
                            )
                            clearResolvedStoreIssue(entitlement.snapshot())
                        }
                    } else {
                        handleAcknowledgementFailure(active, result)
                    }
                }
            },
            onFailure = { result ->
                if (completeAcknowledgement(active)) {
                    handleAcknowledgementFailure(active, result)
                }
            },
        )
    }

    private fun handleAcknowledgementFailure(
        active: ActiveAcknowledgement,
        result: BillingResult,
    ) {
        val purchaseToken = active.purchaseToken
        if (!acknowledgementAttempts.accepts(purchaseToken, active.attempt)) return
        val currentAcknowledgements = entitlement.snapshot().pendingAcknowledgements
        if (purchaseToken !in currentAcknowledgements) {
            // A newer authoritative query already acknowledged or removed it.
            cancelAcknowledgementTracking(purchaseToken, removeGeneration = true)
            clearResolvedStoreIssue(entitlement.snapshot())
            return
        }
        when (
            val action = BillingAcknowledgementFailurePolicy.resolve(
                isTransient = isTransient(result.responseCode),
                completedRetries = active.attempt.completedRetries,
            )
        ) {
            is BillingAcknowledgementFailureAction.Retry ->
                scheduleAcknowledgementRetry(active, action.delayMs)

            BillingAcknowledgementFailureAction.PublishIssue -> {
                // End this bounded chain but retain the durable token. The next
                // foreground observation may start exactly one fresh chain.
                cancelAcknowledgementTracking(purchaseToken, removeGeneration = true)
                _storeIssue.value = BillingStoreIssues.purchaseAcknowledgement()
            }
        }
        // The token stays durably queued until Play confirms acknowledgement
        // or an authoritative ownership query removes it.
    }

    private fun scheduleAcknowledgementRetry(
        active: ActiveAcknowledgement,
        delayMs: Long,
    ) {
        val purchaseToken = active.purchaseToken
        if (
            !acknowledgementAttempts.accepts(purchaseToken, active.attempt) ||
            purchaseToken in acknowledgementRetryTasks
        ) return

        val retryAttempt = acknowledgementAttempts.retry(
            purchaseToken,
            active.attempt,
        ) ?: return
        val retry = active.copy(attempt = retryAttempt)
        lateinit var task: Runnable
        task = Runnable {
            if (acknowledgementRetryTasks[purchaseToken] !== task) return@Runnable
            acknowledgementRetryTasks.remove(purchaseToken)
            if (
                !ended &&
                acknowledgementAttempts.accepts(purchaseToken, retry.attempt)
            ) {
                requestAcknowledgement(purchaseToken, retry.attempt)
            }
        }
        acknowledgementRetryTasks[purchaseToken] = task
        handler.postDelayed(task, delayMs)
    }

    private fun retryStoredAcknowledgements(forceNewGeneration: Boolean = false) {
        entitlement.snapshot().pendingAcknowledgements.forEach {
            startAcknowledgement(it, forceNewGeneration)
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
        val result = client.launchBillingFlow(activity, params)
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restore()
            BillingClient.BillingResponseCode.USER_CANCELED -> showError(
                "Purchase cancelled. You have not been charged.",
                retryable = false,
                allowPurchase = true,
            )
            else -> recoverFromFailedLaunch(purchaseFailureMessage(result))
        }
    }

    private fun recoverFromFailedLaunch(message: String) {
        val recovery = BillingLaunchRecoveryPolicy.resolve(BillingLaunchOutcome.Failed)
        if (recovery.clearProductDetails) {
            productLoadPolicy.invalidate()
            productDetails = null
            deferredProductFailure = null
        }
        retryAction = if (recovery.retryProductLoad) {
            UiRetryAction.LoadProduct
        } else {
            UiRetryAction.None
        }
        showError(
            message = "$message Reload the current Google Play offer before trying again.",
            retryable = recovery.retryProductLoad,
            allowPurchase = recovery.allowImmediateRepurchase,
            discardPrice = recovery.clearProductDetails,
        )
    }

    private fun connect(action: () -> Unit, onFailure: (BillingResult) -> Unit) {
        if (ended) return
        when (client.connectionState) {
            BillingClient.ConnectionState.CONNECTED -> action()
            BillingClient.ConnectionState.CONNECTING -> {
                synchronized(whenConnected) {
                    whenConnected += ConnectedAction(action, onFailure)
                }
                ensureSetupWatchdog(startConnection = false)
            }
            else -> {
                synchronized(whenConnected) {
                    whenConnected += ConnectedAction(action, onFailure)
                }
                ensureSetupWatchdog(startConnection = true)
            }
        }
    }

    private fun ensureSetupWatchdog(startConnection: Boolean) {
        if (ended || connectionAttemptInFlight) return
        connectionAttemptInFlight = true
        connectionGeneration = nextGeneration(connectionGeneration)
        val generation = connectionGeneration
        lateinit var watchdog: Runnable
        watchdog = Runnable {
            if (
                ended ||
                setupWatchdog !== watchdog ||
                generation != connectionGeneration
            ) return@Runnable
            setupWatchdog = null
            connectionAttemptInFlight = false
            val result = timeoutResult("Google Play connection timed out.")
            val actions = synchronized(whenConnected) {
                whenConnected.toList().also { whenConnected.clear() }
            }
            actions.forEach { it.onFailure(result) }
        }
        setupWatchdog = watchdog
        handler.postDelayed(watchdog, SETUP_TIMEOUT_MS)
        if (startConnection) client.startConnection(this)
    }

    private fun cancelSetupWatchdog() {
        setupWatchdog?.let(handler::removeCallbacks)
        setupWatchdog = null
        connectionGeneration = nextGeneration(connectionGeneration)
    }

    private fun scheduleProductWatchdog(generation: Long) {
        productWatchdogs.remove(generation)?.let(handler::removeCallbacks)
        lateinit var watchdog: Runnable
        watchdog = Runnable {
            if (productWatchdogs[generation] !== watchdog) return@Runnable
            productWatchdogs.remove(generation)
            if (!activeProductQueries.remove(generation) || ended) return@Runnable
            if (productLoadPolicy.accepts(generation)) {
                productLoadPolicy.invalidate()
                failProductLoad(
                    "Google Play took too long to load the current price. Retry Google Play.",
                    retryable = true,
                )
            }
        }
        productWatchdogs[generation] = watchdog
        handler.postDelayed(watchdog, OPERATION_TIMEOUT_MS)
    }

    private fun completeProductQuery(generation: Long): Boolean {
        if (!activeProductQueries.remove(generation)) return false
        productWatchdogs.remove(generation)?.let(handler::removeCallbacks)
        return true
    }

    private fun cancelTrackedProductQueries() {
        productWatchdogs.values.forEach(handler::removeCallbacks)
        productWatchdogs.clear()
        activeProductQueries.clear()
    }

    private fun hasCurrentEntitlementQuery(): Boolean =
        activeEntitlementQueries.keys.any(entitlementOperations::accepts)

    private fun scheduleEntitlementWatchdog(active: ActiveEntitlementQuery) {
        entitlementWatchdogs.remove(active.operationId)?.let(handler::removeCallbacks)
        lateinit var watchdog: Runnable
        watchdog = Runnable {
            if (entitlementWatchdogs[active.operationId] !== watchdog) return@Runnable
            entitlementWatchdogs.remove(active.operationId)
            val completed = completeEntitlementQuery(active) ?: return@Runnable
            handleEntitlementFailure(
                timeoutResult("Google Play ownership check timed out."),
                completed,
            )
        }
        entitlementWatchdogs[active.operationId] = watchdog
        handler.postDelayed(watchdog, OPERATION_TIMEOUT_MS)
    }

    private fun completeEntitlementQuery(
        expected: ActiveEntitlementQuery,
    ): ActiveEntitlementQuery? {
        if (activeEntitlementQueries[expected.operationId] != expected) return null
        activeEntitlementQueries.remove(expected.operationId)
        entitlementWatchdogs.remove(expected.operationId)?.let(handler::removeCallbacks)
        return expected
    }

    private fun cancelTrackedEntitlementQueries() {
        entitlementWatchdogs.values.forEach(handler::removeCallbacks)
        entitlementWatchdogs.clear()
        entitlementRetryTasks.values.forEach(handler::removeCallbacks)
        entitlementRetryTasks.clear()
        activeEntitlementQueries.clear()
    }

    private fun scheduleAcknowledgementWatchdog(active: ActiveAcknowledgement) {
        acknowledgementWatchdogs.remove(active.purchaseToken)?.let(handler::removeCallbacks)
        lateinit var watchdog: Runnable
        watchdog = Runnable {
            if (acknowledgementWatchdogs[active.purchaseToken] !== watchdog) return@Runnable
            acknowledgementWatchdogs.remove(active.purchaseToken)
            if (!completeAcknowledgement(active)) return@Runnable
            handleAcknowledgementFailure(
                active,
                timeoutResult("Google Play purchase acknowledgement timed out."),
            )
        }
        acknowledgementWatchdogs[active.purchaseToken] = watchdog
        handler.postDelayed(watchdog, OPERATION_TIMEOUT_MS)
    }

    private fun completeAcknowledgement(active: ActiveAcknowledgement): Boolean {
        if (activeAcknowledgements[active.purchaseToken] != active) return false
        activeAcknowledgements.remove(active.purchaseToken)
        acknowledgementWatchdogs.remove(active.purchaseToken)?.let(handler::removeCallbacks)
        return acknowledgementAttempts.accepts(active.purchaseToken, active.attempt)
    }

    private fun cancelAcknowledgementTracking(
        purchaseToken: String,
        removeGeneration: Boolean,
    ) {
        activeAcknowledgements.remove(purchaseToken)
        acknowledgementWatchdogs.remove(purchaseToken)?.let(handler::removeCallbacks)
        acknowledgementRetryTasks.remove(purchaseToken)?.let(handler::removeCallbacks)
        if (removeGeneration) acknowledgementAttempts.clear(purchaseToken)
    }

    private fun cancelAcknowledgementTrackingNotIn(pendingTokens: Set<String>) {
        val trackedTokens = buildSet {
            addAll(acknowledgementAttempts.trackedKeys())
            addAll(activeAcknowledgements.keys)
            addAll(acknowledgementRetryTasks.keys)
        }
        (trackedTokens - pendingTokens).forEach {
            cancelAcknowledgementTracking(it, removeGeneration = true)
        }
    }

    private fun clearResolvedStoreIssue(snapshot: BillingEntitlementSnapshot) {
        when (_storeIssue.value?.kind) {
            BillingStoreIssueKind.EntitlementPersistence -> _storeIssue.value = null
            BillingStoreIssueKind.PurchaseAcknowledgement -> {
                if (snapshot.pendingAcknowledgements.isEmpty()) _storeIssue.value = null
            }
            null -> Unit
        }
    }

    private fun failActiveOperationsAfterDisconnect(result: BillingResult) {
        val productGenerations = activeProductQueries.toList()
        productGenerations.forEach { generation ->
            if (completeProductQuery(generation) && productLoadPolicy.accepts(generation)) {
                productLoadPolicy.invalidate()
                failProductLoad(connectionFailureMessage(result), retryable = true)
            }
        }

        val entitlementQueries = activeEntitlementQueries.values.toList()
        entitlementQueries.forEach { active ->
            completeEntitlementQuery(active)?.let {
                handleEntitlementFailure(result, it)
            }
        }

        val acknowledgements = activeAcknowledgements.values.toList()
        acknowledgements.forEach { active ->
            if (completeAcknowledgement(active)) {
                handleAcknowledgementFailure(active, result)
            }
        }
    }

    private fun timeoutResult(message: String): BillingResult = localFailureResult(
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        message,
    )

    private fun localFailureResult(responseCode: Int, message: String): BillingResult =
        BillingResult.newBuilder()
            .setResponseCode(responseCode)
            .setDebugMessage(message)
            .build()

    private fun nextGeneration(current: Long): Long =
        if (current == Long.MAX_VALUE) 1L else current + 1L

    private fun failProductLoad(message: String, retryable: Boolean) {
        // Do not hide progress for a restore/purchase that began while the
        // independent product query was still completing.
        if (
            _uiState.value.phase == BillingPhase.Restoring ||
            _uiState.value.phase == BillingPhase.Purchasing ||
            _uiState.value.phase == BillingPhase.Pending
        ) {
            deferredProductFailure = message
            retryAction = UiRetryAction.LoadProduct
            return
        }
        retryAction = UiRetryAction.LoadProduct
        showError(
            message = message,
            retryable = retryable,
            allowPurchase = false,
            discardPrice = true,
        )
    }

    private fun showError(
        message: String,
        retryable: Boolean,
        allowPurchase: Boolean,
        discardPrice: Boolean = false,
    ) {
        transition(
            BillingUiEvent.Error(
                message = message,
                retryable = retryable,
                allowPurchase = allowPurchase,
                discardPrice = discardPrice,
            )
        )
    }

    private fun transition(event: BillingUiEvent) {
        _uiState.value = BillingUiReducer.reduce(_uiState.value, event)
    }

    @Suppress("DEPRECATION") // Still handle SERVICE_TIMEOUT from older Play Store processes.
    private fun isTransient(responseCode: Int): Boolean = when (responseCode) {
        BillingClient.BillingResponseCode.SERVICE_TIMEOUT,
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.NETWORK_ERROR,
        BillingClient.BillingResponseCode.ERROR,
        -> true
        else -> false
    }

    private fun connectionFailureMessage(result: BillingResult): String = when (result.responseCode) {
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
            "Google Play Billing is unavailable on this device or account. Check Play Store setup, then retry."
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED ->
            "This Google Play version does not support in-app purchases. Update the Play Store and retry."
        else -> "TacMap could not connect to Google Play. Check your connection and retry."
    }

    private fun productFailureMessage(result: BillingResult): String = when (result.responseCode) {
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE ->
            "The TacMap unlock product is not available for this Play Store account."
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
            "Google Play Billing is unavailable. Check the Play Store app and account, then retry."
        else -> "TacMap could not load the Google Play price. Check your connection and retry."
    }

    private fun purchaseFailureMessage(result: BillingResult): String = when (result.responseCode) {
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE ->
            "The TacMap unlock product is not available for this Play Store account."
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
            "Google Play Billing is unavailable. Check the Play Store app and account, then try again."
        BillingClient.BillingResponseCode.DEVELOPER_ERROR ->
            "This build cannot start the configured Google Play purchase. Install the Play Store release and try again."
        else -> "Google Play could not start the purchase. Check your connection and try again."
    }

    private data class ConnectedAction(
        val action: () -> Unit,
        val onFailure: (BillingResult) -> Unit,
    )

    private data class ActiveEntitlementQuery(
        val operationId: Long,
        val userInitiated: Boolean,
        val completedRetries: Int,
    )

    private data class ActiveAcknowledgement(
        val purchaseToken: String,
        val attempt: BillingAttempt,
    )

    private enum class UiRetryAction { None, LoadProduct, Restore }

    companion object {
        /** Must match the in-app product ID created in Play Console. */
        const val PRODUCT_ID = "unlock_full"
        private const val PREFERENCES_NAME = "billing_entitlement"
        private const val SETUP_TIMEOUT_MS = 15_000L
        private const val OPERATION_TIMEOUT_MS = 20_000L
    }
}
