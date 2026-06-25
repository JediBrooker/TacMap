package com.tacmap.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tacmap.billing.BillingManager
import com.tacmap.billing.PaywallScreen
import com.tacmap.billing.TrialManager
import com.tacmap.map.MapScreen

class MainActivity : ComponentActivity() {

    private lateinit var trial: TrialManager
    private lateinit var billing: BillingManager
    private val appLock by lazy { AppLock(this) }

    /// True when the App Lock PIN gate must be shown. Armed on launch and on
    /// every pause, so returning to the app re-prompts.
    private val locked = mutableStateOf(false)

    // Bumped on resume so the trial-expiry gate re-evaluates when the user
    // returns to the app (e.g. days later) without a cold restart.
    private val resumeTick = mutableLongStateOf(System.currentTimeMillis())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        trial = TrialManager(this)
        billing = BillingManager(this).also { it.start() }
        locked.value = appLock.isEnabled
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                if (locked.value) {
                    AppLockScreen(appLock) { locked.value = false }
                    return@MaterialTheme
                }
                val purchased by billing.isPurchased.collectAsState()
                val price by billing.priceText.collectAsState()
                val now by resumeTick

                val unlocked = purchased || trial.isTrialActive(now)

                if (unlocked) {
                    var showPaywall by remember { mutableStateOf(false) }
                    Box(Modifier.fillMaxSize()) {
                        MapScreen(
                            isPurchased = purchased,
                            trialDaysRemaining = trial.daysRemaining(now),
                            onUnlock = { showPaywall = true },
                        )
                        // On-demand paywall (from the menu's Unlock row during trial).
                        if (showPaywall && !purchased) {
                            PaywallScreen(
                                priceText = price,
                                trialDaysRemaining = trial.daysRemaining(now),
                                onUnlock = { billing.launchPurchase(this@MainActivity) },
                                onRestore = { billing.restore() },
                                onRedeem = { openPlayRedeem() },
                                onClose = { showPaywall = false },
                            )
                        }
                    }
                } else {
                    PaywallScreen(
                        priceText = price,
                        trialDaysRemaining = trial.daysRemaining(now),
                        onUnlock = { billing.launchPurchase(this@MainActivity) },
                        onRestore = { billing.restore() },
                        onRedeem = { openPlayRedeem() },
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Re-arm the App Lock so returning to the app re-prompts for the PIN.
        if (appLock.isEnabled) locked.value = true
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate the trial window and re-check entitlement on return.
        // This also picks up an unlock granted by a Play promo code the user
        // redeemed while away (via "Redeem code" → Play Store).
        resumeTick.longValue = System.currentTimeMillis()
        billing.restore()
    }

    /**
     * Open the Play Store's own code-redemption screen for promo codes
     * (Play Console → your in-app product → Promotions). A redeemed code
     * grants the real `unlock_full` entitlement, which [BillingManager.restore]
     * picks up on return — no app-side code validation, and store-safe.
     * Falls back to the browser if the Play Store app isn't installed.
     */
    private fun openPlayRedeem() {
        val redeem = Uri.parse("https://play.google.com/redeem")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, redeem).setPackage("com.android.vending"))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, redeem))
        }
    }

    override fun onDestroy() {
        if (::billing.isInitialized) {
            billing.end()
        }
        super.onDestroy()
    }
}
