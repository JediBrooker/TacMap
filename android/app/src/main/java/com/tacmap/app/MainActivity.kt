package com.tacmap.app

import android.content.ActivityNotFoundException
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
import com.tacmap.map.AuthBoundChangeController
import com.tacmap.util.DataKey

class MainActivity : ComponentActivity() {

    private lateinit var trial: TrialManager
    private lateinit var billing: BillingManager
    private val appLock by lazy { AppLock(this) }

    // True when PIN gate needs to show. Armed on launch + every pause
    // so coming back to the app re-prompts.
    private val locked = mutableStateOf(false)
    private val missionKeyReady = mutableStateOf(false)
    private val missionKeyError = mutableStateOf<String?>(null)
    private lateinit var credentialLauncher: ActivityResultLauncher<Intent>
    private lateinit var authBoundChangeLauncher: ActivityResultLauncher<Intent>
    private lateinit var pdfImportLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var geoJsonImportLauncher: ActivityResultLauncher<Array<String>>
    private val pendingPdfImportUri = mutableStateOf<Uri?>(null)
    private val pendingGeoJsonImportUri = mutableStateOf<Uri?>(null)
    private val authBoundChangeController by lazy {
        AuthBoundChangeController(object : AuthBoundChangeController.KeyProtection {
            override val isAuthBound: Boolean get() = DataKey.isAuthBound
            override fun setAuthBound(enabled: Boolean) = DataKey.setAuthBound(enabled)
        })
    }

    // bump on resume so trial-expiry check re-fires when user comes back
    // (e.g. days later) without a cold restart
    private val resumeTick = mutableLongStateOf(System.currentTimeMillis())
    private var restoreAfterRedeem = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        credentialLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            runCatching { DataKey.key() }
                .onSuccess {
                    (application as TacticalApp).trackRecorder.reloadAfterUnlock()
                    missionKeyError.value = null
                    missionKeyReady.value = true
                }
                .onFailure { missionKeyError.value = it.message }
        }
        // Activity-owned because the platform credential screen intentionally
        // pauses the app and tears down MapScreen with the mission key.
        authBoundChangeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val completion = authBoundChangeController.completeCredential(result.resultCode == Activity.RESULT_OK)
            completion.error?.let { missionKeyError.value = it }
            if (result.resultCode == Activity.RESULT_OK && completion.error == null) {
                missionKeyError.value = null
                missionKeyReady.value = true
            }
        }
        // These launchers belong to the Activity because the document picker
        // pauses the app and MapScreen is removed while the mission key locks.
        pdfImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            pendingPdfImportUri.value = uri
        }
        geoJsonImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            pendingGeoJsonImportUri.value = uri
        }
        trial = TrialManager(this)
        // Constructing BillingManager is local-only. It connects to Play only
        // after the user opens Unlock, taps purchase, or explicitly restores.
        billing = BillingManager(this)
        locked.value = appLock.isEnabled
        if (locked.value) {
            DataKey.lock()
            missionKeyReady.value = false
        } else {
            prepareMissionKey()
        }

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
                    AppLockScreen(appLock) {
                        locked.value = false
                        prepareMissionKey()
                    }
                    return@MaterialTheme
                }
                if (!missionKeyReady.value) {
                    MissionKeyUnlockScreen(missionKeyError.value, ::requestMissionKeyUnlock)
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
                            pendingPdfImportUri = pendingPdfImportUri.value,
                            onRequestPdfImport = pdfImportLauncher::launch,
                            onPdfImportConsumed = { uri ->
                                if (pendingPdfImportUri.value == uri) pendingPdfImportUri.value = null
                            },
                            pendingGeoJsonImportUri = pendingGeoJsonImportUri.value,
                            onRequestGeoJsonImport = geoJsonImportLauncher::launch,
                            onGeoJsonImportConsumed = { uri ->
                                if (pendingGeoJsonImportUri.value == uri) pendingGeoJsonImportUri.value = null
                            },
                            onRequestAuthBoundChange = ::requestAuthBoundChange,
                            onUnlock = {
                                billing.start()
                                showPaywall = true
                            },
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
        // Never retain the mission DEK behind an App Lock/background boundary.
        // Device-bound mode can unwrap again locally; auth-bound mode requires
        // a fresh platform authentication window.
        (application as TacticalApp).trackRecorder.onMissionKeyLock()
        DataKey.lock()
        // Tear down MapScreen and its observers for both key modes. Otherwise
        // device-bound mode could automatically unwrap the DEK again from a
        // retained background sync/store callback immediately after lock().
        missionKeyReady.value = false
        // re-arm app lock so coming back requires PIN again
        if (appLock.isEnabled) locked.value = true
    }

    override fun onResume() {
        super.onResume()
        // Re-check the local trial clock only. Store entitlement refresh is an
        // explicit Restore action so a normal foreground transition has no egress.
        resumeTick.longValue = System.currentTimeMillis()
        billing.refreshLocalEntitlement()
        if (!appLock.isEnabled && !DataKey.isAuthBound && !missionKeyReady.value) {
            // Device-bound mode needs no network or user prompt; rebuild the
            // mission stores only after the Activity is foreground again.
            prepareMissionKey()
        }
        if (restoreAfterRedeem) {
            restoreAfterRedeem = false
            billing.restore()
        }
    }

    /**
     * Opens the Play Store promo code redemption screen
     * (Play Console > in-app product > Promotions). Redeemed code grants
     * the real `unlock_full` entitlement that [BillingManager.restore]
     * picks up on return - no app-side validation needed.
     * Falls back to browser if Play Store app isnt installed.
     */
    private fun openPlayRedeem() {
        restoreAfterRedeem = true
        val redeem = Uri.parse("https://play.google.com/redeem")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, redeem).setPackage("com.android.vending"))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, redeem))
        }
    }

    private fun requestMissionKeyUnlock() {
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        @Suppress("DEPRECATION")
        val intent = keyguard.createConfirmDeviceCredentialIntent(
            "Unlock mission data",
            "Confirm your device credential to decrypt maps and mission data."
        )
        if (intent == null) {
            missionKeyError.value = "No device credential is available for this protected key."
        } else {
            credentialLauncher.launch(intent)
        }
    }

    private fun requestAuthBoundChange(target: Boolean) {
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        when (val request = authBoundChangeController.request(target, keyguard.isDeviceSecure)) {
            AuthBoundChangeController.Request.NoChange -> Unit
            is AuthBoundChangeController.Request.Error -> missionKeyError.value = request.message
            AuthBoundChangeController.Request.PromptCredential -> {
                @Suppress("DEPRECATION")
                val intent = keyguard.createConfirmDeviceCredentialIntent(
                    "Confirm mission-data protection change",
                    "Authenticate to change how the mission-data key is protected."
                )
                if (intent == null) {
                    authBoundChangeController.cancelPending()
                    missionKeyError.value = "No device lockscreen is set, so this can't be changed."
                } else {
                    authBoundChangeLauncher.launch(intent)
                }
            }
        }
    }

    private fun prepareMissionKey() {
        missionKeyReady.value = runCatching { DataKey.key(); true }.getOrElse {
            missionKeyError.value = it.message
            false
        }
    }

    override fun onDestroy() {
        if (::billing.isInitialized) {
            billing.end()
        }
        super.onDestroy()
    }
}
