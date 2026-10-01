package com.tacmap.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/**
 * Fingerprint or face unlock for App Lock, as iOS offers Face ID / Touch ID.
 * Uses the platform BiometricPrompt (Android 10+), so no extra library; older
 * devices keep the PIN only. The PIN always remains the fallback, and a
 * biometric match never touches the PIN lockout counters.
 */
object AppLockBiometric {

    /** True when the device has a fingerprint or face enrolled that apps may use. */
    fun isAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val manager = context.getSystemService(BiometricManager::class.java) ?: return false
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        } else {
            @Suppress("DEPRECATION")
            manager.canAuthenticate()
        }
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Shows the system biometric prompt. [onResult] is called once, on the
     * main thread: true on a match, false if the user cancels, chooses the
     * PIN, or the prompt fails. Returns a signal to cancel it, or null when
     * biometrics aren't available.
     */
    fun authenticate(
        context: Context,
        title: String,
        usePinLabel: String,
        onResult: (Boolean) -> Unit,
    ): CancellationSignal? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !isAvailable(context)) return null
        val activity = context.findActivity() ?: return null
        val executor = activity.mainExecutor
        var delivered = false
        fun deliver(success: Boolean) {
            if (delivered) return
            delivered = true
            onResult(success)
        }
        val builder = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setNegativeButton(usePinLabel, executor) { _, _ -> deliver(false) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        }
        val cancel = CancellationSignal()
        builder.build().authenticate(
            cancel,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = deliver(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = deliver(false)
            },
        )
        return cancel
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
