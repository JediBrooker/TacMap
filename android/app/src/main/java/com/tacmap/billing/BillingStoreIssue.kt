package com.tacmap.billing

import com.tacmap.localization.L10n

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
    val title: String,
    val message: String,
)

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
        title = L10n.text("Google Play needs attention"),
        message = L10n.text("TacMap saved your purchase, but Google Play has not confirmed its acknowledgement. Your access remains available. Retry soon to avoid Play automatically refunding an unacknowledged purchase."),
    )

    fun entitlementPersistence(): BillingStoreIssue = BillingStoreIssue(
        kind = BillingStoreIssueKind.EntitlementPersistence,
        title = L10n.text("Unlock status needs attention"),
        message = L10n.text("TacMap checked Google Play but couldn't save the latest unlock status securely. Your existing access was kept. Free some device storage, then retry."),
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
            TextButton(onClick = onRetry) { Text(L10n.text("Retry Google Play")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(L10n.text("Not now")) }
        },
    )
}
