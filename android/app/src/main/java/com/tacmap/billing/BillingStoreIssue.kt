package com.tacmap.billing

import com.tacmap.localization.Messages
import com.tacmap.localization.LocalizedMessage

import androidx.compose.material3.AlertDialog
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

internal enum class BillingRootContent {
    Unlocked,
    HardPaywall,
}

internal data class BillingRootPresentation(
    val content: BillingRootContent,
    val storeIssue: BillingStoreIssue?,
)

/** Keeps actionable store issues above both the app and hard paywall roots. */
internal object BillingRootPresentationPolicy {
    fun resolve(isUnlocked: Boolean, storeIssue: BillingStoreIssue?): BillingRootPresentation =
        BillingRootPresentation(
            content = if (isUnlocked) {
                BillingRootContent.Unlocked
            } else {
                BillingRootContent.HardPaywall
            },
            storeIssue = storeIssue,
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
