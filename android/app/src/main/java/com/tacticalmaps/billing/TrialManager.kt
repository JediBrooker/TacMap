package com.tacticalmaps.billing

import android.content.Context

/**
 * Tracks the free-trial window. The first launch is stamped in
 * SharedPreferences and the trial runs for [TRIAL_DAYS] days from then;
 * after that the user must buy the one-time unlock (see [BillingManager]).
 *
 * NOTE: SharedPreferences is cleared on uninstall, so a reinstall restarts
 * the trial. That's an accepted trade-off for a low-price one-time unlock —
 * a tamper-proof trial would need a server-side check keyed to the account.
 */
class TrialManager(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        // Stamp first launch exactly once.
        if (!prefs.contains(KEY_FIRST_LAUNCH)) {
            prefs.edit().putLong(KEY_FIRST_LAUNCH, System.currentTimeMillis()).apply()
        }
    }

    private val firstLaunchMillis: Long
        get() = prefs.getLong(KEY_FIRST_LAUNCH, System.currentTimeMillis())

    private val trialEndMillis: Long
        get() = firstLaunchMillis + TRIAL_MILLIS

    fun isTrialActive(now: Long = System.currentTimeMillis()): Boolean = now < trialEndMillis

    /** Whole days remaining, rounded up (so "2.3 days left" reads as 3), 0 once expired. */
    fun daysRemaining(now: Long = System.currentTimeMillis()): Int {
        val remaining = trialEndMillis - now
        if (remaining <= 0L) return 0
        return ((remaining + DAY_MILLIS - 1) / DAY_MILLIS).toInt()
    }

    companion object {
        const val TRIAL_DAYS = 3
        private const val PREFS = "entitlement"
        private const val KEY_FIRST_LAUNCH = "first_launch_millis"
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
        private const val TRIAL_MILLIS = TRIAL_DAYS * DAY_MILLIS
    }
}
