package com.tacmap.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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

    // True when PIN gate needs to show. Armed on launch + every pause
    // so coming back to the app re-prompts.
    private val locked = mutableStateOf(false)

    // bump on resume so trial-expiry check re-fires when user comes back
    // (e.g. days later) without a cold restart
    private val resumeTick = mutableLongStateOf(System.currentTimeMillis())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        trial = TrialManager(this)
        billing = BillingManager(this).also { it.start() }
        locked.value = appLock.isEnabled

        // OPSEC: keep the map (w/ live position) out of recents thumbnail,
        // screenshots, screen recordings. Follows user's setting reactively.
        val opsec = (application as TacticalApp).opsec
        lifecycleScope.launch {
            opsec.blockScreenCapture.collect { block ->
                if (block) {
                    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setRecentsScreenshotEnabled(!block)
                }
            }
        }

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
                        // on-demand paywall from the menu Unlock row during trial
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
        // re-arm app lock so coming back requires PIN again
        if (appLock.isEnabled) locked.value = true
    }

    override fun onResume() {
        super.onResume()
        // re-check trial window + entitlement on return. Also picks up
        // an unlock from a Play promo code redeemed while we were backgrounded.
        resumeTick.longValue = System.currentTimeMillis()
        billing.restore()
    }

    /**
     * Opens the Play Store promo code redemption screen
     * (Play Console > in-app product > Promotions). Redeemed code grants
     * the real `unlock_full` entitlement that [BillingManager.restore]
     * picks up on return - no app-side validation needed.
     * Falls back to browser if Play Store app isnt installed.
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
