package com.tacticalmaps.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import com.tacticalmaps.billing.BillingManager
import com.tacticalmaps.billing.PaywallScreen
import com.tacticalmaps.billing.TrialManager
import com.tacticalmaps.map.MapScreen

class MainActivity : ComponentActivity() {

    private lateinit var trial: TrialManager
    private lateinit var billing: BillingManager

    // Bumped on resume so the trial-expiry gate re-evaluates when the user
    // returns to the app (e.g. days later) without a cold restart.
    private val resumeTick = mutableLongStateOf(System.currentTimeMillis())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        trial = TrialManager(this)
        billing = BillingManager(this).also { it.start() }
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val purchased by billing.isPurchased.collectAsState()
                val price by billing.priceText.collectAsState()
                val now by resumeTick

                val unlocked = purchased || trial.isTrialActive(now)

                if (unlocked) {
                    MapScreen()
                } else {
                    PaywallScreen(
                        priceText = price,
                        trialDaysRemaining = trial.daysRemaining(now),
                        onUnlock = { billing.launchPurchase(this@MainActivity) },
                        onRestore = { billing.restore() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate the trial window and re-check entitlement on return.
        resumeTick.longValue = System.currentTimeMillis()
        billing.restore()
    }
}
