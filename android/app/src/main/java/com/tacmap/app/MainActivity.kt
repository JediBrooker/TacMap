package com.tacmap.app


import com.tacmap.localization.LocalizedMessage
import com.tacmap.localization.Messages
import com.tacmap.localization.displayMessage
import com.tacmap.localization.L10n

import android.content.ActivityNotFoundException
import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tacmap.billing.BillingManager
import com.tacmap.billing.BillingRootContent
import com.tacmap.billing.BillingRootPresentationPolicy
import com.tacmap.billing.BillingStoreIssueAlert
import com.tacmap.billing.PaywallScreen
import com.tacmap.billing.TrialManager
import com.tacmap.map.MapScreen
import com.tacmap.map.AuthBoundChangeController
import com.tacmap.models.AndroidTrackRecordingPreflight
import com.tacmap.models.LiveMapLocationPermissionPolicy
import com.tacmap.models.LiveMapLocationState
import com.tacmap.models.LocationAccess
import com.tacmap.models.LocationAccessPolicy
import com.tacmap.models.TrackRecordingService
import com.tacmap.models.TrackRecordingSettingsTarget
import com.tacmap.util.DataKey
import com.tacmap.util.retryExpiredSensitiveClipboard

class MainActivity : ComponentActivity() {

    private lateinit var trial: TrialManager
    private lateinit var billing: BillingManager
    private val appLock: AppLock
        get() = (application as TacticalApp).appLock

    // True when PIN gate needs to show. Armed on launch + every pause
    // so coming back to the app re-prompts.
    private val locked = mutableStateOf(false)
    private val missionKeyReady = mutableStateOf(false)
    private val missionKeyError = mutableStateOf<LocalizedMessage?>(null)
    private lateinit var credentialLauncher: ActivityResultLauncher<Intent>
    private lateinit var authBoundChangeLauncher: ActivityResultLauncher<Intent>
    private lateinit var symbolPackImportLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var pdfImportLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var geoJsonImportLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var mbtilesImportLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var kmlImportLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var liveMapLocationPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var trackRecordingPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var pendingImportCoordinator: PendingDocumentImportCoordinator
    private val pendingDocumentImport = mutableStateOf<PendingDocumentImport?>(null)
    private val showNotificationPermissionExplanation = mutableStateOf(false)
    private val liveMapLocationAccess = mutableStateOf(LocationAccess.Denied)
    private val liveMapLocationState = mutableStateOf(LiveMapLocationState.NotRequested)
    private val trackRecordingStartGate = TrackRecordingStartLifecycleGate()
    private val trackRecordingResumeObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) continuePendingTrackRecordingStart()
    }
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
        lifecycle.addObserver(trackRecordingResumeObserver)
        pendingImportCoordinator = PendingDocumentImportCoordinator(
            restorePendingDocumentImport(savedInstanceState)
        )
        pendingDocumentImport.value = pendingImportCoordinator.current()
        credentialLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            runCatching { DataKey.key() }
                .onSuccess {
                    (application as TacticalApp).trackRecorder.reloadAfterUnlock()
                    missionKeyError.value = null
                    missionKeyReady.value = true
                    continuePendingTrackRecordingStart()
                }
                .onFailure { missionKeyError.value = it.displayMessage }
        }
        // Activity-owned because the platform credential screen intentionally
        // pauses the app and tears down MapScreen with the mission key.
        authBoundChangeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val completion = authBoundChangeController.completeCredential(result.resultCode == Activity.RESULT_OK)
            completion.pendingError?.let { missionKeyError.value = it }
            if (result.resultCode == Activity.RESULT_OK && completion.error == null) {
                missionKeyError.value = null
                missionKeyReady.value = true
            }
        }
        // These launchers belong to the Activity because the document picker
        // pauses the app and MapScreen is removed while the mission key locks.
        symbolPackImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            receiveDocumentImportResult(DocumentImportKind.SYMBOL_PACK, uri)
        }
        pdfImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            receiveDocumentImportResult(DocumentImportKind.PDF, uri)
        }
        geoJsonImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            receiveDocumentImportResult(DocumentImportKind.GEO_JSON, uri)
        }
        mbtilesImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            receiveDocumentImportResult(DocumentImportKind.MBTILES, uri)
        }
        kmlImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            receiveDocumentImportResult(DocumentImportKind.KML, uri)
        }
        liveMapLocationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            refreshLiveMapLocationAccess()
        }
        trackRecordingPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            trackRecordingStartGate.onPermissionResult()
            refreshLiveMapLocationAccess()
            continuePendingTrackRecordingStart()
        }
        refreshLiveMapLocationAccess()
        trial = TrialManager(this)
        // Observe permanent ownership for the Activity lifetime. This contacts
        // Google Play on launch and throttled foreground transitions, but does
        // not load product/price data until a paywall is visible.
        billing = BillingManager(this)
        billing.start()
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
                    MissionKeyUnlockScreen(missionKeyError.value?.text, ::requestMissionKeyUnlock)
                    return@MaterialTheme
                }
                val purchased by billing.isPurchased.collectAsState()
                val billingState by billing.uiState.collectAsState()
                val storeIssue by billing.storeIssue.collectAsState()
                val now by resumeTick

                val unlocked = purchased || trial.isTrialActive(now)
                val billingPresentation = BillingRootPresentationPolicy.resolve(
                    isUnlocked = unlocked,
                    storeIssue = storeIssue,
                )

                Box(Modifier.fillMaxSize()) {
                    when (billingPresentation.content) {
                        BillingRootContent.Unlocked -> {
                            var showPaywall by remember { mutableStateOf(false) }
                            MapScreen(
                                appLock = appLock,
                                isPurchased = purchased,
                                trialDaysRemaining = trial.daysRemaining(now),
                                pendingDocumentImport = pendingDocumentImport.value,
                                onRequestDocumentImport = ::requestDocumentImport,
                                onClaimDocumentImport = { token ->
                                    pendingImportCoordinator.claim(token) != null
                                },
                                onCompleteDocumentImport = ::completeDocumentImport,
                                onAbandonDocumentImport = pendingImportCoordinator::abandon,
                                onRequestAuthBoundChange = ::requestAuthBoundChange,
                                liveMapLocationState = liveMapLocationState.value,
                                onRequestLiveMapLocation = ::requestLiveMapLocation,
                                onOpenLiveMapLocationSettings = {
                                    openTrackRecordingSettings(TrackRecordingSettingsTarget.AppPermissions)
                                },
                                onRequestTrackRecording = ::requestTrackRecordingStart,
                                onOpenTrackRecordingSettings = ::openTrackRecordingSettings,
                                onUnlock = {
                                    showPaywall = true
                                },
                            )
                            // on-demand paywall from the menu Unlock row during trial
                            if (showPaywall && !purchased) {
                                PaywallScreen(
                                    billingState = billingState,
                                    trialDaysRemaining = trial.daysRemaining(now),
                                    onLoadProduct = { billing.loadProduct() },
                                    onUnlock = { billing.launchPurchase(this@MainActivity) },
                                    onRestore = { billing.restore() },
                                    onRetry = { billing.retry() },
                                    onRedeem = { openPlayRedeem() },
                                    onClose = { showPaywall = false },
                                )
                            }
                        }

                        BillingRootContent.HardPaywall -> PaywallScreen(
                            billingState = billingState,
                            trialDaysRemaining = trial.daysRemaining(now),
                            onLoadProduct = { billing.loadProduct() },
                            onUnlock = { billing.launchPurchase(this@MainActivity) },
                            onRestore = { billing.restore() },
                            onRetry = { billing.retry() },
                            onRedeem = { openPlayRedeem() },
                        )
                    }
                    billingPresentation.storeIssue?.let { issue ->
                        BillingStoreIssueAlert(
                            issue = issue,
                            onRetry = { billing.retryStoreIssue() },
                            onDismiss = { billing.dismissStoreIssue() },
                        )
                    }
                    if (showNotificationPermissionExplanation.value) {
                        AlertDialog(
                            onDismissRequest = { showNotificationPermissionExplanation.value = false },
                            title = { Text(L10n.text("Recording notification is off")) },
                            text = {
                                Text(
                                    Messages.recordingNotificationHidden()
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = { showNotificationPermissionExplanation.value = false }
                                ) { Text(L10n.text("Continue recording")) }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Reduce an eligible v3 client to egress-only presence before the
        // mission key and its screen-owned stores are torn down.
        (application as TacticalApp).unitSyncRuntime.onActivityPausing()
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

    override fun onSaveInstanceState(outState: Bundle) {
        pendingImportCoordinator.savedSnapshot()?.let { pending ->
            outState.putString(PENDING_IMPORT_TOKEN, pending.token)
            outState.putString(PENDING_IMPORT_KIND, pending.kind.savedValue)
            outState.putString(PENDING_IMPORT_URI, pending.uri)
            outState.putBoolean(PENDING_IMPORT_GRANT, pending.persistableGrantTaken)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        // Stop background egress now; Unit Sync reconnects only after the
        // mission key is available and MapScreen attaches fresh stores.
        (application as TacticalApp).unitSyncRuntime.onActivityForegrounded()
        // The trial remains local. Permanent ownership is refreshed against
        // Play on a monotonic 15-minute throttle; failures retain known-good access.
        resumeTick.longValue = System.currentTimeMillis()
        billing.onAppForeground()
        refreshLiveMapLocationAccess()
        window.decorView.post { requestInitialLiveMapLocationIfReady() }
        (application as TacticalApp).trackRecorder.onLocationAccessChanged(currentLocationAccess())
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) retryExpiredSensitiveClipboard(this)
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
            L10n.text("Unlock mission data"),
            L10n.text("Confirm your device credential to decrypt maps and mission data.")
        )
        if (intent == null) {
            missionKeyError.value = Messages.missionKeyNoDeviceCredentialIsAvailableForThisProtectedKeyMessage()
        } else {
            credentialLauncher.launch(intent)
        }
    }

    private fun requestDocumentImport(kind: DocumentImportKind) {
        when (kind) {
            DocumentImportKind.SYMBOL_PACK -> symbolPackImportLauncher.launch(kind.mimeTypes)
            DocumentImportKind.PDF -> pdfImportLauncher.launch(kind.mimeTypes)
            DocumentImportKind.GEO_JSON -> geoJsonImportLauncher.launch(kind.mimeTypes)
            DocumentImportKind.MBTILES -> mbtilesImportLauncher.launch(kind.mimeTypes)
            DocumentImportKind.KML -> kmlImportLauncher.launch(kind.mimeTypes)
        }
    }

    private fun receiveDocumentImportResult(kind: DocumentImportKind, uri: Uri?) {
        uri ?: return
        pendingImportCoordinator.current()?.let(::releaseDocumentImportGrant)
        val grantTaken = runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        }.getOrDefault(false)
        val pending = pendingImportCoordinator.publish(kind, uri.toString(), grantTaken)
        if (persistPendingDocumentImport(pending)) {
            pendingDocumentImport.value = pending
        } else {
            pendingImportCoordinator = PendingDocumentImportCoordinator()
            pendingDocumentImport.value = null
            releaseDocumentImportGrant(pending)
        }
    }

    private fun completeDocumentImport(token: String) {
        val pending = pendingImportCoordinator.current()?.takeIf { it.token == token } ?: return
        // Durable clear first. If it fails, abandon the claim and retain the
        // grant so a recreated Activity can safely retry instead of duplicating
        // a PDF/MBTiles import whose completion marker was lost.
        if (!persistPendingDocumentImport(null)) {
            pendingImportCoordinator.abandon(token)
            return
        }
        val completed = pendingImportCoordinator.complete(token) ?: run {
            persistPendingDocumentImport(pending)
            return
        }
        pendingDocumentImport.value = null
        releaseDocumentImportGrant(completed)
    }

    private fun releaseDocumentImportGrant(pending: PendingDocumentImport) {
        val uri = runCatching { Uri.parse(pending.uri) }.getOrNull() ?: return
        if (pending.persistableGrantTaken) {
            runCatching {
                contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        runCatching {
            revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun restorePendingDocumentImport(savedInstanceState: Bundle?): PendingDocumentImport? {
        fun fromValues(token: String?, kind: String?, uri: String?, grant: Boolean): PendingDocumentImport? {
            if (token.isNullOrBlank() || uri.isNullOrBlank()) return null
            val parsedKind = DocumentImportKind.fromSavedValue(kind) ?: return null
            return PendingDocumentImport(token, parsedKind, uri, grant)
        }

        fromValues(
            savedInstanceState?.getString(PENDING_IMPORT_TOKEN),
            savedInstanceState?.getString(PENDING_IMPORT_KIND),
            savedInstanceState?.getString(PENDING_IMPORT_URI),
            savedInstanceState?.getBoolean(PENDING_IMPORT_GRANT, false) ?: false,
        )?.let { return it }

        val prefs = getSharedPreferences(PENDING_IMPORT_PREFS, Context.MODE_PRIVATE)
        return fromValues(
            prefs.getString(PENDING_IMPORT_TOKEN, null),
            prefs.getString(PENDING_IMPORT_KIND, null),
            prefs.getString(PENDING_IMPORT_URI, null),
            prefs.getBoolean(PENDING_IMPORT_GRANT, false),
        )
    }

    private fun persistPendingDocumentImport(pending: PendingDocumentImport?): Boolean {
        val edit = getSharedPreferences(PENDING_IMPORT_PREFS, Context.MODE_PRIVATE).edit().clear()
        if (pending != null) {
            edit.putString(PENDING_IMPORT_TOKEN, pending.token)
                .putString(PENDING_IMPORT_KIND, pending.kind.savedValue)
                .putString(PENDING_IMPORT_URI, pending.uri)
                .putBoolean(PENDING_IMPORT_GRANT, pending.persistableGrantTaken)
        }
        val saved = edit.commit()
        if (!saved) {
            missionKeyError.value = Messages.missionKeyCouldNotPreserveThePendingDocumentImportAcrossProcessMessage()
        }
        return saved
    }

    private fun requestAuthBoundChange(target: Boolean) {
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        when (val request = authBoundChangeController.request(target, keyguard.isDeviceSecure)) {
            AuthBoundChangeController.Request.NoChange -> Unit
            is AuthBoundChangeController.Request.Error -> missionKeyError.value = request.pendingMessage
            AuthBoundChangeController.Request.PromptCredential -> {
                @Suppress("DEPRECATION")
                val intent = keyguard.createConfirmDeviceCredentialIntent(
                    L10n.text("Confirm mission-data protection change"),
                    L10n.text("Authenticate to change how the mission-data key is protected.")
                )
                if (intent == null) {
                    authBoundChangeController.cancelPending()
                    missionKeyError.value = Messages.displayNoDeviceLockscreenIsSetSoThisCanTMessage()
                } else {
                    authBoundChangeLauncher.launch(intent)
                }
            }
        }
    }

    private fun prepareMissionKey() {
        missionKeyReady.value = runCatching { DataKey.key(); true }.getOrElse {
            missionKeyError.value = it.displayMessage
            false
        }
        if (missionKeyReady.value) {
            continuePendingTrackRecordingStart()
            window.decorView.post { requestInitialLiveMapLocationIfReady() }
        }
    }

    private fun requestTrackRecordingStart() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return

        val requests = mutableListOf<String>()
        if (currentLocationAccess() != LocationAccess.Precise) {
            requests += Manifest.permission.ACCESS_FINE_LOCATION
            requests += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requests += Manifest.permission.POST_NOTIFICATIONS
        }

        when (trackRecordingStartGate.request(permissionRequired = requests.isNotEmpty())) {
            TrackRecordingStartLifecycleGate.RequestDecision.IgnoreDuplicate -> return
            TrackRecordingStartLifecycleGate.RequestDecision.LaunchPermissions -> {
                if (Manifest.permission.ACCESS_FINE_LOCATION in requests) {
                    getSharedPreferences(LIVE_MAP_LOCATION_PREFS, Context.MODE_PRIVATE)
                        .edit { putBoolean(LIVE_MAP_LOCATION_REQUESTED, true) }
                    refreshLiveMapLocationAccess()
                }
                (application as TacticalApp).trackRecorder.awaitPermissionRequest(
                    if (currentLocationAccess() == LocationAccess.Precise) {
                        Messages.recordingAwaitingNotificationMessage()
                    } else {
                        Messages.recordingAwaitingPermissionMessage()
                    }
                )
                trackRecordingPermissionLauncher.launch(requests.toTypedArray())
            }
            TrackRecordingStartLifecycleGate.RequestDecision.ContinueNow -> {
                continuePendingTrackRecordingStart()
            }
        }
    }

    private fun requestLiveMapLocation() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        refreshLiveMapLocationAccess()
        if (liveMapLocationAccess.value == LocationAccess.Precise) return
        if (liveMapLocationState.value != LiveMapLocationState.NotRequested) {
            openTrackRecordingSettings(TrackRecordingSettingsTarget.AppPermissions)
            return
        }
        getSharedPreferences(LIVE_MAP_LOCATION_PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(LIVE_MAP_LOCATION_REQUESTED, true) }
        refreshLiveMapLocationAccess()
        liveMapLocationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }

    private fun requestInitialLiveMapLocationIfReady() {
        if (locked.value || !missionKeyReady.value) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (!LiveMapLocationPermissionPolicy.shouldRequestOnInitialMapPresentation(
                liveMapLocationState.value
            )
        ) return
        requestLiveMapLocation()
    }

    private fun continuePendingTrackRecordingStart() {
        if (!trackRecordingStartGate.takeContinuation(
                isResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                prerequisitesReady = !locked.value && missionKeyReady.value,
            )
        ) return

        val recorder = (application as TacticalApp).trackRecorder
        val access = currentLocationAccess()
        val gpsEnabled = runCatching {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager)
                .isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)

        if (!recorder.requestStart(access, gpsEnabled)) return
        val preflight = AndroidTrackRecordingPreflight.inspect(
            context = this,
            activityVisible = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            locationAccess = access,
            gpsEnabled = gpsEnabled,
        )
        if (!preflight.canStart) {
            recorder.failRecording(
                preflight.pendingMessage ?: Messages.recordingPrerequisitesUnavailableMessage(),
                preflight.settingsTarget,
            )
            return
        }
        if (!recorder.prepareStart()) return
        runCatching { TrackRecordingService.start(this) }
            .onFailure {
                recorder.failRecording(Messages.recordingBackgroundStartFailedMessage(it.message ?: "null"))
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            showNotificationPermissionExplanationOnce()
        }
    }

    private fun currentLocationAccess(): LocationAccess = LocationAccessPolicy.resolve(
        fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED,
        coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED,
    )

    private fun refreshLiveMapLocationAccess() {
        val access = currentLocationAccess()
        liveMapLocationAccess.value = access
        val requested = getSharedPreferences(LIVE_MAP_LOCATION_PREFS, Context.MODE_PRIVATE)
            .getBoolean(LIVE_MAP_LOCATION_REQUESTED, false)
        liveMapLocationState.value = LiveMapLocationPermissionPolicy.resolveUiState(
            access = access,
            permissionRequested = requested,
            policyRestricted = isLiveMapLocationPolicyRestricted(),
        )
    }

    private fun isLiveMapLocationPolicyRestricted(): Boolean {
        val userManager = getSystemService(Context.USER_SERVICE) as? UserManager ?: return false
        return userManager.hasUserRestriction(UserManager.DISALLOW_SHARE_LOCATION)
    }

    private fun showNotificationPermissionExplanationOnce() {
        val prefs = getSharedPreferences("track_recording_ui", Context.MODE_PRIVATE)
        if (prefs.getBoolean("notification_denial_explained_v1", false)) return
        prefs.edit { putBoolean("notification_denial_explained_v1", true) }
        showNotificationPermissionExplanation.value = true
    }

    private fun openTrackRecordingSettings(target: TrackRecordingSettingsTarget) {
        val intent = when (target) {
            TrackRecordingSettingsTarget.AppPermissions -> Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            )
            TrackRecordingSettingsTarget.LocationServices ->
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        }
        runCatching { startActivity(intent) }
    }

    override fun onDestroy() {
        lifecycle.removeObserver(trackRecordingResumeObserver)
        if (::billing.isInitialized) {
            billing.end()
        }
        super.onDestroy()
    }

    private companion object {
        const val LIVE_MAP_LOCATION_PREFS = "live_map_location_ui_v1"
        const val LIVE_MAP_LOCATION_REQUESTED = "permission_requested"
        const val PENDING_IMPORT_PREFS = "pending_document_import_v1"
        const val PENDING_IMPORT_TOKEN = "pending_import_token"
        const val PENDING_IMPORT_KIND = "pending_import_kind"
        const val PENDING_IMPORT_URI = "pending_import_uri"
        const val PENDING_IMPORT_GRANT = "pending_import_grant"
    }
}
