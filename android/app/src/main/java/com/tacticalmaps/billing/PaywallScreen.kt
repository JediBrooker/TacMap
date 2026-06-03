package com.tacticalmaps.billing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
 * Full-screen paywall shown once the free trial has lapsed and the unlock
 * has not been purchased. Blocks the app until the user buys the one-time
 * unlock or restores a previous purchase.
 *
 * @param priceText localized price from Play (e.g. "$5.00"); null while loading.
 * @param trialDaysRemaining >0 means the trial is still running (soft prompt);
 *        0 means it has expired (hard gate copy).
 */
@Composable
fun PaywallScreen(
    priceText: String?,
    trialDaysRemaining: Int,
    onUnlock: () -> Unit,
    onRestore: () -> Unit,
    onClose: (() -> Unit)? = null,
) {
    val expired = trialDaysRemaining <= 0
    Box(modifier = Modifier.fillMaxSize().background(Background)) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "TacticalMaps",
            color = HudGreen,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (expired) "Your free trial has ended" else "Unlock the full version",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            if (expired)
                "Your ${TrialManager.TRIAL_DAYS}-day free trial is over. " +
                    "Make a one-time purchase to keep using TacticalMaps — " +
                    "live MGRS, GeoPDF maps, NATO APP-6 symbology and GeoJSON export."
            else
                "You're on the free trial ($trialDaysRemaining " +
                    "${if (trialDaysRemaining == 1) "day" else "days"} left). " +
                    "Unlock now for permanent access.",
            color = Color(0xFFB8C4BC),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onUnlock,
            enabled = priceText != null,
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
                priceText?.let { "Unlock Full Version  ·  $it" } ?: "Loading price…",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onRestore) {
            Text("Restore purchase", color = HudOrange, fontSize = 14.sp)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "One-time purchase. No subscription.",
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
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF9AA69E))
            }
        }
    }
}
