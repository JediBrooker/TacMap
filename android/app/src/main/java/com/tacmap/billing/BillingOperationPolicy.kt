package com.tacmap.billing

/**
 * Gives every paywall appearance its own product request. Results from an
 * older appearance/retry are ignored, so a stale ProductDetails object cannot
 * replace a newer one.
 */
internal class BillingProductLoadPolicy {
    private var generation = 0L

    fun beginPaywallLoad(): Long = nextGeneration()

    /** Invalidates the ProductDetails used by a failed synchronous launch. */
    fun invalidate(): Long = nextGeneration()

    fun accepts(resultGeneration: Long): Boolean = resultGeneration == generation

    private fun nextGeneration(): Long {
        generation = if (generation == Long.MAX_VALUE) 1L else generation + 1L
        return generation
    }
}

internal enum class BillingLaunchOutcome {
    Started,
    Cancelled,
    AlreadyOwned,
    Failed,
}

internal data class BillingLaunchRecovery(
    val clearProductDetails: Boolean,
    val retryProductLoad: Boolean,
    val allowImmediateRepurchase: Boolean,
)

internal object BillingLaunchRecoveryPolicy {
    fun resolve(outcome: BillingLaunchOutcome): BillingLaunchRecovery = when (outcome) {
        BillingLaunchOutcome.Started,
        BillingLaunchOutcome.AlreadyOwned,
        -> BillingLaunchRecovery(false, false, false)

        BillingLaunchOutcome.Cancelled -> BillingLaunchRecovery(
            clearProductDetails = false,
            retryProductLoad = false,
            allowImmediateRepurchase = true,
        )

        BillingLaunchOutcome.Failed -> BillingLaunchRecovery(
            clearProductDetails = true,
            retryProductLoad = true,
            allowImmediateRepurchase = false,
        )
    }
}

internal sealed interface BillingAcknowledgementFailureAction {
    data class Retry(val delayMs: Long) : BillingAcknowledgementFailureAction
    data object PublishIssue : BillingAcknowledgementFailureAction
}

internal object BillingAcknowledgementFailurePolicy {
    fun resolve(
        isTransient: Boolean,
        completedRetries: Int,
    ): BillingAcknowledgementFailureAction = if (
        BillingRetryPolicy.shouldRetry(isTransient, completedRetries)
    ) {
        BillingAcknowledgementFailureAction.Retry(
            BillingRetryPolicy.delayMs(completedRetries)
        )
    } else {
        BillingAcknowledgementFailureAction.PublishIssue
    }
}

/** Token used to invalidate delayed work after a newer explicit operation. */
internal class BillingRetryGeneration {
    private var generation = 0L

    fun invalidate(): Long {
        generation = if (generation == Long.MAX_VALUE) 1L else generation + 1L
        return generation
    }

    fun current(): Long = generation
    fun isCurrent(candidate: Long): Boolean = candidate == generation
}

/**
 * Orders ownership queries against purchase callbacks. Only the latest query
 * may publish an authoritative answer; observing a completed purchase also
 * supersedes every query that began before that purchase.
 */
internal class BillingEntitlementOperationPolicy {
    private var latestOperation = 0L

    fun beginQuery(): Long = advance()

    fun supersedeWithPurchase(): Long = advance()

    fun accepts(queryOperation: Long): Boolean = queryOperation == latestOperation

    private fun advance(): Long {
        latestOperation = if (latestOperation == Long.MAX_VALUE) 1L else latestOperation + 1L
        return latestOperation
    }
}

/** Pure identity used by watchdog/retry tests and callback guards. */
internal data class BillingAttempt(
    val generation: Long,
    val completedRetries: Int,
)

/**
 * Small callback gate for token-keyed operations. A new operation invalidates
 * callbacks and watchdogs from every prior attempt for the same key, while a
 * retry stays in the same generation.
 */
internal class BillingAttemptGate<Key> {
    private val generations = mutableMapOf<Key, Long>()
    private val activeKeys = mutableSetOf<Key>()

    fun hasOperation(key: Key): Boolean = key in activeKeys

    fun trackedKeys(): Set<Key> = activeKeys.toSet()

    /** Starts one chain, or declines while that key already has active work. */
    fun beginIfIdle(key: Key): BillingAttempt? =
        if (hasOperation(key)) null else begin(key)

    fun begin(key: Key): BillingAttempt {
        val current = generations[key] ?: 0L
        val next = if (current == Long.MAX_VALUE) 1L else current + 1L
        generations[key] = next
        activeKeys += key
        return BillingAttempt(generation = next, completedRetries = 0)
    }

    fun retry(key: Key, attempt: BillingAttempt): BillingAttempt? =
        if (accepts(key, attempt)) {
            attempt.copy(completedRetries = attempt.completedRetries + 1)
        } else {
            null
        }

    fun accepts(key: Key, attempt: BillingAttempt): Boolean =
        key in activeKeys && generations[key] == attempt.generation

    fun clear(key: Key) {
        val current = generations[key] ?: return
        generations[key] = if (current == Long.MAX_VALUE) 1L else current + 1L
        activeKeys -= key
    }

    fun clear() {
        generations.clear()
        activeKeys.clear()
    }
}
