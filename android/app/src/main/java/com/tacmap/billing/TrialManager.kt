package com.tacmap.billing

import android.content.Context

/**
 * Tracks the free-trial window. Local-only: no Google Play Services, no network.
 *
 * We used to mirror the first-launch stamp to Google Block Store so it survived
 * uninstall/reinstall (anti trial-reset), matching the iOS Keychain. Block Store
 * is a Play Services component, so it's out for the no-phone-home posture.
 *
 * The honest consequence: on Android the trial now RESETS on uninstall/reinstall.
 * There is no Google-free store on Android that survives app deletion (iOS
 * Keychain does, which is why iOS keeps the protection). A determined user can
 * re-trial by reinstalling. For a one-time-unlock app that's a modest revenue
 * nuisance, not a security issue, and it's the price of not touching Play
 * Services. The clock-rollback guard below still holds within an install.
 */
class TrialManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        if (!prefs.contains(KEY_FIRST_LAUNCH)) {
            prefs.edit().putLong(KEY_FIRST_LAUNCH, System.currentTimeMillis()).apply()
        }
        touchLatestSeen(System.currentTimeMillis())
    }

    // ---- clock-rollback guard -------------------------------------------

    /** Bump the high-water mark, returns effective "now" so setting the system
     *  clock back can't extend the trial. */
    private fun touchLatestSeen(now: Long): Long {
        val seen = prefs.getLong(KEY_LATEST_SEEN, 0L)
        val effective = maxOf(now, seen)
        if (effective > seen) {
            prefs.edit().putLong(KEY_LATEST_SEEN, effective).apply()
        }
        return effective
    }

    // ---- public API -----------------------------------------------------

    private val firstLaunchMillis: Long
        get() = prefs.getLong(KEY_FIRST_LAUNCH, System.currentTimeMillis())

    private val trialEndMillis: Long
        get() = firstLaunchMillis + TRIAL_MILLIS

    fun isTrialActive(now: Long = System.currentTimeMillis()): Boolean =
        touchLatestSeen(now) < trialEndMillis

    /** Days left rounded up (2.3 days -> 3), 0 when expired. */
    fun daysRemaining(now: Long = System.currentTimeMillis()): Int {
        val remaining = trialEndMillis - touchLatestSeen(now)
        if (remaining <= 0L) return 0
        return ((remaining + DAY_MILLIS - 1) / DAY_MILLIS).toInt()
    }

    companion object {
        const val TRIAL_DAYS = 3
        private const val PREFS = "entitlement"
        private const val KEY_FIRST_LAUNCH = "first_launch_millis"
        private const val KEY_LATEST_SEEN = "latest_seen_millis"
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
        private const val TRIAL_MILLIS = TRIAL_DAYS * DAY_MILLIS
    }
}
