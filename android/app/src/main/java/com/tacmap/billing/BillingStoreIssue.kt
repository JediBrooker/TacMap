package com.tacmap.billing

import com.tacmap.localization.Messages
import com.tacmap.localization.LocalizedMessage

import com.tacmap.ui.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

enum class BillingStoreIssueKind {
    PurchaseAcknowledgement,
    EntitlementPersistence,
}

data class BillingStoreIssue(
    val kind: BillingStoreIssueKind,
    val titleMessage: LocalizedMessage,
    val pendingMessage: LocalizedMessage,
) {
    val title: String get() = titleMessage.text
    val message: String get() = pendingMessage.text
}

/**
 * 3.1 dropped the hard paywall: the map is always the root, a lapsed trial just
 * turns it into the free viewer. Kept as an enum so the root switch in
 * MainActivity still reads as a decision rather than a bare composable.
 */
internal enum class BillingRootContent {
    Unlocked,
}

internal data class BillingRootPresentation(
    val content: BillingRootContent,
    val storeIssue: BillingStoreIssue?,
    /** false = free viewer, the paid tools open the on-demand paywall instead */
    val isFullVersion: Boolean,
)

/** Keeps actionable store issues above the app root whatever the entitlement is. */
internal object BillingRootPresentationPolicy {
    fun resolve(isFullVersion: Boolean, storeIssue: BillingStoreIssue?): BillingRootPresentation =
        BillingRootPresentation(
            content = BillingRootContent.Unlocked,
            storeIssue = storeIssue,
            isFullVersion = isFullVersion,
        )
}

internal object BillingStoreIssues {
    fun purchaseAcknowledgement(): BillingStoreIssue = BillingStoreIssue(
        kind = BillingStoreIssueKind.PurchaseAcknowledgement,
        titleMessage = Messages.billingGooglePlayNeedsAttentionMessage(),
        pendingMessage = Messages.billingTacmapSavedYourPurchaseButGooglePlayHasNotMessage(),
    )

    fun entitlementPersistence(): BillingStoreIssue = BillingStoreIssue(
        kind = BillingStoreIssueKind.EntitlementPersistence,
        titleMessage = Messages.billingUnlockStatusNeedsAttentionMessage(),
        pendingMessage = Messages.billingTacmapCheckedGooglePlayButCouldnTSaveTheMessage(),
    )
}

@Composable
fun BillingStoreIssueAlert(
    issue: BillingStoreIssue,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(issue.title) },
        text = { Text(issue.message) },
        confirmButton = {
            TextButton(onClick = onRetry) { Text(Messages.billingRetryGooglePlay()) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(Messages.billingNotNow()) }
        },
    )
}
