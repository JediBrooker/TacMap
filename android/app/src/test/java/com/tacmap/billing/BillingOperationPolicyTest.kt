package com.tacmap.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingOperationPolicyTest {
    @Test fun everyPaywallAppearanceStartsFreshAndRejectsThePreviousProductResult() {
        val policy = BillingProductLoadPolicy()

        val firstAppearance = policy.beginPaywallLoad()
        assertTrue(policy.accepts(firstAppearance))

        val reopenedPaywall = policy.beginPaywallLoad()
        assertFalse(policy.accepts(firstAppearance))
        assertTrue(policy.accepts(reopenedPaywall))
    }

    @Test fun failedSynchronousLaunchRequiresFreshProductBeforeAnotherPurchase() {
        val failed = BillingLaunchRecoveryPolicy.resolve(BillingLaunchOutcome.Failed)

        assertTrue(failed.clearProductDetails)
        assertTrue(failed.retryProductLoad)
        assertFalse(failed.allowImmediateRepurchase)

        val cancelled = BillingLaunchRecoveryPolicy.resolve(BillingLaunchOutcome.Cancelled)
        assertFalse(cancelled.clearProductDetails)
        assertFalse(cancelled.retryProductLoad)
        assertTrue(cancelled.allowImmediateRepurchase)
    }

    @Test fun terminalAcknowledgementFailurePublishesAnActionableStructuredIssue() {
        assertEquals(
            BillingAcknowledgementFailureAction.PublishIssue,
            BillingAcknowledgementFailurePolicy.resolve(
                isTransient = false,
                completedRetries = 0,
            ),
        )

        val issue = BillingStoreIssues.purchaseAcknowledgement()
        assertEquals(BillingStoreIssueKind.PurchaseAcknowledgement, issue.kind)
        assertTrue(issue.title.isNotBlank())
        assertTrue(issue.message.contains("Retry"))
        assertTrue(issue.message.contains("access remains available"))
    }

    @Test fun exhaustedTransientAcknowledgementFailureAlsoPublishesTheIssue() {
        assertEquals(
            BillingAcknowledgementFailureAction.PublishIssue,
            BillingAcknowledgementFailurePolicy.resolve(
                isTransient = true,
                completedRetries = BillingRetryPolicy.MAX_TRANSIENT_RETRIES,
            ),
        )
    }

    @Test fun newerExplicitOperationInvalidatesAQueuedRetryGeneration() {
        val generation = BillingRetryGeneration()
        val delayedRetry = generation.invalidate()
        assertTrue(generation.isCurrent(delayedRetry))

        val explicitRestore = generation.invalidate()

        assertFalse(generation.isCurrent(delayedRetry))
        assertTrue(generation.isCurrent(explicitRestore))
    }

    @Test fun completedPurchaseSupersedesAnOlderAuthoritativeEmptyQuery() {
        val policy = BillingEntitlementOperationPolicy()
        val queryBeforePurchase = policy.beginQuery()

        policy.supersedeWithPurchase()

        assertFalse(policy.accepts(queryBeforePurchase))
    }

    @Test fun explicitRestoreRequiresItsOwnFreshQueryResult() {
        val policy = BillingEntitlementOperationPolicy()
        val foregroundQuery = policy.beginQuery()
        val restoreQuery = policy.beginQuery()

        assertFalse(policy.accepts(foregroundQuery))
        assertTrue(policy.accepts(restoreQuery))
    }

    @Test fun acknowledgementWatchdogAndCallbackAreBoundToTheirOperationGeneration() {
        val gate = BillingAttemptGate<String>()
        val firstAttempt = gate.begin("purchase-token")
        val retryAttempt = checkNotNull(gate.retry("purchase-token", firstAttempt))

        assertTrue(gate.accepts("purchase-token", firstAttempt))
        assertTrue(gate.accepts("purchase-token", retryAttempt))
        assertEquals(1, retryAttempt.completedRetries)

        val explicitRetry = gate.begin("purchase-token")
        assertFalse(gate.accepts("purchase-token", firstAttempt))
        assertFalse(gate.accepts("purchase-token", retryAttempt))
        assertTrue(gate.accepts("purchase-token", explicitRetry))

        gate.clear("purchase-token")
        val operationAfterClear = gate.begin("purchase-token")
        assertFalse(gate.accepts("purchase-token", explicitRetry))
        assertTrue(gate.accepts("purchase-token", operationAfterClear))
    }

    @Test fun persistenceExhaustionHasAnActionableGlobalIssue() {
        val issue = BillingStoreIssues.entitlementPersistence()

        assertEquals(BillingStoreIssueKind.EntitlementPersistence, issue.kind)
        assertTrue(issue.title.isNotBlank())
        assertTrue(issue.message.contains("retry", ignoreCase = true))
    }

    @Test fun exhaustedAcknowledgementAllowsExactlyOneFreshForegroundChain() {
        val gate = BillingAttemptGate<String>()
        val token = "durable-purchase-token"
        val exhaustedChain = gate.beginIfIdle(token)!!
        gate.clear(token)

        val foregroundChain = gate.beginIfIdle(token)
        val duplicateForegroundChain = gate.beginIfIdle(token)

        assertFalse(gate.accepts(token, exhaustedChain))
        assertTrue(foregroundChain != null)
        assertTrue(gate.accepts(token, foregroundChain!!))
        assertEquals(null, duplicateForegroundChain)
    }

    @Test fun globalStoreIssueOverlaysTheHardPaywall() {
        val issue = BillingStoreIssues.entitlementPersistence()

        val presentation = BillingRootPresentationPolicy.resolve(
            isUnlocked = false,
            storeIssue = issue,
        )

        assertEquals(BillingRootContent.HardPaywall, presentation.content)
        assertEquals(issue, presentation.storeIssue)
    }
}
