package com.tacmap.billing

/**
 * The locally durable part of the Play entitlement.
 *
 * There is deliberately no timestamp here. A store outage or a changed device
 * clock is not evidence that a previously verified, permanent purchase was
 * revoked.
 */
internal data class BillingEntitlementSnapshot(
    val isPurchased: Boolean,
    val pendingAcknowledgements: Set<String> = emptySet(),
)

internal object BillingEntitlementPolicy {
    /** Non-authoritative results (timeouts, transport/store errors) are no-ops. */
    fun resolveQuery(
        current: BillingEntitlementSnapshot,
        authoritative: Boolean,
        purchasedTokens: Set<String>,
        unacknowledgedTokens: Set<String>,
    ): BillingEntitlementSnapshot {
        if (!authoritative) return current
        return BillingEntitlementSnapshot(
            isPurchased = purchasedTokens.isNotEmpty(),
            pendingAcknowledgements = if (purchasedTokens.isEmpty()) {
                emptySet()
            } else {
                unacknowledgedTokens.intersect(purchasedTokens)
            },
        )
    }
}

/** Small persistence seam so entitlement decisions can be exercised on the JVM. */
internal interface BillingEntitlementPersistence {
    /** Null means this install has not yet migrated from the legacy cache. */
    fun readDurableEntitlement(): Boolean?
    fun readLegacyEntitlement(): Boolean
    fun readPendingAcknowledgements(): Set<String>
    fun write(snapshot: BillingEntitlementSnapshot): Boolean
}

/**
 * Applies ownership changes only after they are synchronously persisted.
 * Callers must only invoke [recordAuthoritativeQuery] for a successful Play
 * `queryPurchasesAsync` result.
 */
internal class BillingEntitlementRepository(
    private val persistence: BillingEntitlementPersistence,
) {
    private var current = load()

    @Synchronized
    fun snapshot(): BillingEntitlementSnapshot = current.copy(
        pendingAcknowledgements = current.pendingAcknowledgements.toSet(),
    )

    /** Persist the grant and acknowledgement work before publishing access. */
    @Synchronized
    fun recordPurchase(purchaseToken: String, needsAcknowledgement: Boolean): Boolean {
        val pending = if (needsAcknowledgement) {
            current.pendingAcknowledgements + purchaseToken
        } else {
            current.pendingAcknowledgements - purchaseToken
        }
        return commit(BillingEntitlementSnapshot(isPurchased = true, pending))
    }

    /**
     * Replace local state from a complete, successful Play ownership query.
     * An empty purchased set is the only path that clears known-good access.
     */
    @Synchronized
    fun recordAuthoritativeQuery(
        purchasedTokens: Set<String>,
        unacknowledgedTokens: Set<String>,
    ): Boolean = commit(
        BillingEntitlementPolicy.resolveQuery(
            current = current,
            authoritative = true,
            purchasedTokens = purchasedTokens,
            unacknowledgedTokens = unacknowledgedTokens,
        )
    )

    /** A failed disk write intentionally leaves harmless retry work in place. */
    @Synchronized
    fun recordAcknowledged(purchaseToken: String): Boolean = commit(
        current.copy(pendingAcknowledgements = current.pendingAcknowledgements - purchaseToken)
    )

    private fun load(): BillingEntitlementSnapshot {
        val durable = persistence.readDurableEntitlement()
        val loaded = BillingEntitlementSnapshot(
            isPurchased = durable ?: persistence.readLegacyEntitlement(),
            pendingAcknowledgements = persistence.readPendingAcknowledgements(),
        )
        // Migrate an affirmative v1 cache without consulting its old timestamp.
        // If this write fails the legacy value remains the source on next launch.
        if (durable == null) persistence.write(loaded)
        return loaded
    }

    private fun commit(candidate: BillingEntitlementSnapshot): Boolean {
        if (candidate == current) return true
        if (!persistence.write(candidate)) return false
        current = candidate
        return true
    }
}

internal object BillingRetryPolicy {
    const val MAX_TRANSIENT_RETRIES = 3

    private val delaysMs = longArrayOf(1_000L, 3_000L, 10_000L)

    fun shouldRetry(isTransient: Boolean, completedRetries: Int): Boolean =
        isTransient && completedRetries < MAX_TRANSIENT_RETRIES

    fun delayMs(completedRetries: Int): Long =
        delaysMs[completedRetries.coerceIn(delaysMs.indices)]
}

internal object BillingForegroundRefreshPolicy {
    const val MIN_REFRESH_INTERVAL_MS = 15L * 60L * 1000L

    fun shouldRefresh(lastStartedElapsedMs: Long?, nowElapsedMs: Long, queryInFlight: Boolean): Boolean {
        if (queryInFlight) return false
        val last = lastStartedElapsedMs ?: return true
        // elapsedRealtime is monotonic, but treat an impossible rollback as due.
        return nowElapsedMs < last || nowElapsedMs - last >= MIN_REFRESH_INTERVAL_MS
    }
}
