package com.tacmap.billing

import com.tacmap.localization.L10n

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Background = Color(0xFF151916) // launcher_background
private val HudGreen = Color(0xFF8CF28C)
private val HudOrange = Color(0xFFF2A24A)

/**
 * Full-screen paywall. Shows after trial lapses if user hasn't purchased.
 * Blocks the app until they buy or restore.
 *
 * The same loading/error/retry contract is used for both hard and soft gates.
 * Product loading begins only while this screen is actually composed.
 *
 * @param trialDaysRemaining >0 = trial still running (soft prompt),
 *        0 = expired (hard gate).
 */
@Composable
fun PaywallScreen(
    billingState: BillingUiState,
    trialDaysRemaining: Int,
    onLoadProduct: () -> Unit,
    onUnlock: () -> Unit,
    onRestore: () -> Unit,
    onRetry: () -> Unit,
    onRedeem: () -> Unit,
    onClose: (() -> Unit)? = null,
) {
    val expired = trialDaysRemaining <= 0
    val busy = billingState.phase == BillingPhase.Restoring ||
        billingState.phase == BillingPhase.Purchasing

    LaunchedEffect(Unit) { onLoadProduct() }

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "TacMap",
            color = HudGreen,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (expired) L10n.text("Your free trial has ended") else L10n.text("Unlock the full version"),
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            if (expired)
                L10n.text("Your %1\$s-day free trial is over. ", TrialManager.TRIAL_DAYS) +
                    L10n.text("Make a one-time purchase to keep using TacMap — ") +
                    L10n.text("live MGRS, GeoPDF maps, NATO APP-6 symbology and GeoJSON export.")
            else
                L10n.text("You're on the free trial (%1\$s left). ", L10n.quantity("day", trialDaysRemaining)) +
                    L10n.text("Unlock now for permanent access."),
            color = Color(0xFFB8C4BC),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onUnlock,
            enabled = billingState.purchaseEnabled,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = HudGreen,
                contentColor = Color(0xFF0E140F),
                disabledContainerColor = Color(0xFF2A3A30),
                disabledContentColor = Color(0xFF7A867E),
            ),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when (billingState.phase) {
                    BillingPhase.Connecting -> L10n.text("Connecting to Google Play…")
                    BillingPhase.LoadingProduct, BillingPhase.Idle -> L10n.text("Loading price…")
                    BillingPhase.Restoring -> L10n.text("Restoring purchase…")
                    BillingPhase.Purchasing -> L10n.text("Opening Google Play…")
                    BillingPhase.Pending -> L10n.text("Payment pending")
                    BillingPhase.Ready, BillingPhase.Error -> billingState.priceText
                        ?.let { L10n.text("Unlock Full Version  ·  %1\$s", it) }
                        ?: L10n.text("Unlock unavailable")
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(6.dp))
        billingState.message?.let { message ->
            Text(
                message,
                color = if (
                    billingState.phase == BillingPhase.Error ||
                    billingState.phase == BillingPhase.Pending
                ) HudOrange else Color(0xFFB8C4BC),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        if (billingState.retryable) {
            TextButton(onClick = onRetry) {
                Text(L10n.text("Retry Google Play"), color = HudGreen, fontSize = 14.sp)
            }
        }
        TextButton(onClick = onRestore, enabled = !busy) {
            Text(
                if (billingState.phase == BillingPhase.Restoring) L10n.text("Restoring…") else L10n.text("Restore purchase"),
                color = if (busy) Color(0xFF7A867E) else HudOrange,
                fontSize = 14.sp,
            )
        }
        TextButton(onClick = onRedeem, enabled = !busy) {
            Text(
                L10n.text("Redeem code on Google Play"),
                color = if (busy) Color(0xFF7A867E) else HudOrange,
                fontSize = 14.sp,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            L10n.text("One-time purchase. No subscription."),
            color = Color(0xFF7A867E),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
        if (onClose != null) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = L10n.text("Close"), tint = Color(0xFF9AA69E))
            }
        }
    }
}
