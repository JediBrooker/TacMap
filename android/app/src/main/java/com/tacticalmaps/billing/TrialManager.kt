package com.tacticalmaps.billing

import android.content.Context
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData

/**
 * Tracks the free-trial window. Same synchronous API as before
 * (isTrialActive / daysRemaining), so MainActivity needs no changes beyond
 * optionally awaiting [restoreFromBlockStore] at startup.
 *
 * Parity with the iOS Keychain version:
 *  1. The first-launch timestamp is mirrored to **Block Store**, which
 *     survives uninstall/reinstall on devices with Google Play services.
 *     On reinstall, prefs are empty, so we re-seed them from Block Store —
 *     the trial does NOT restart.
 *  2. A monotonically-increasing "latest seen" timestamp blocks the
 *     set-the-clock-back trick: effective now = max(wall clock, latest seen).
 *  3. On de-Googled devices Block Store calls fail silently and behaviour
 *     degrades to the old prefs-only model (accepted fallback).
 *
 * Gradle: implementation("com.google.android.gms:play-services-auth-blockstore:16.4.0")
 */
class TrialManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        if (!prefs.contains(KEY_FIRST_LAUNCH)) {
            // Don't stamp yet — Block Store may hold the real first launch
            // from a previous install. Kick off the async restore; if Block
            // Store has nothing (true first install or no Play services),
            // stamp now.
            restoreFromBlockStore()
        }
        touchLatestSeen(System.currentTimeMillis())
    }

    /**
     * Re-seed prefs from Block Store after a reinstall, or write the
     * first-launch stamp to both if neither has one. Fire-and-forget; the
     * synchronous getters below fall back to "now" until it lands, which is
     * at worst briefly generous, never exploitable long-term.
     */
    fun restoreFromBlockStore(onComplete: (() -> Unit)? = null) {
        val client = Blockstore.getClient(appContext)
        val request = RetrieveBytesRequest.Builder()
            .setKeys(listOf(KEY_FIRST_LAUNCH, KEY_LATEST_SEEN))
            .build()
        client.retrieveBytes(request)
            .addOnSuccessListener { result ->
                val map = result.blockstoreDataMap
                val storedFirst = map[KEY_FIRST_LAUNCH]?.bytes?.toLongOrNull()
                val storedSeen = map[KEY_LATEST_SEEN]?.bytes?.toLongOrNull()

                if (storedFirst != null) {
                    if (!prefs.contains(KEY_FIRST_LAUNCH)) {
                        prefs.edit().putLong(KEY_FIRST_LAUNCH, storedFirst).apply()
                    }
                } else {
                    stampFirstLaunchEverywhere()
                }
                if (storedSeen != null) {
                    touchLatestSeen(storedSeen)
                }
                onComplete?.invoke()
            }
            .addOnFailureListener {
                // No Play services / Block Store unavailable → prefs-only.
                if (!prefs.contains(KEY_FIRST_LAUNCH)) stampFirstLaunchEverywhere()
                onComplete?.invoke()
            }
    }

    private fun stampFirstLaunchEverywhere() {
        val now = System.currentTimeMillis()
        if (!prefs.contains(KEY_FIRST_LAUNCH)) {
            prefs.edit().putLong(KEY_FIRST_LAUNCH, now).apply()
        }
        writeToBlockStore(KEY_FIRST_LAUNCH, prefs.getLong(KEY_FIRST_LAUNCH, now))
    }

    private fun writeToBlockStore(key: String, value: Long) {
        val data = StoreBytesData.Builder()
            .setKey(key)
            .setBytes(value.toString().toByteArray(Charsets.UTF_8))
            .setShouldBackupToCloud(false) // device-bound, like Keychain config
            .build()
        Blockstore.getClient(appContext).storeBytes(data) // best-effort
    }

    private fun ByteArray.toLongOrNull(): Long? =
        toString(Charsets.UTF_8).toLongOrNull()

    // ---- clock-rollback guard -------------------------------------------

    /** Advance the high-water mark; returns effective "now". */
    private fun touchLatestSeen(now: Long): Long {
        val seen = prefs.getLong(KEY_LATEST_SEEN, 0L)
        val effective = maxOf(now, seen)
        if (effective > seen) {
            prefs.edit().putLong(KEY_LATEST_SEEN, effective).apply()
            writeToBlockStore(KEY_LATEST_SEEN, effective)
        }
        return effective
    }

    // ---- public API (unchanged) -----------------------------------------

    private val firstLaunchMillis: Long
        get() = prefs.getLong(KEY_FIRST_LAUNCH, System.currentTimeMillis())

    private val trialEndMillis: Long
        get() = firstLaunchMillis + TRIAL_MILLIS

    fun isTrialActive(now: Long = System.currentTimeMillis()): Boolean =
        touchLatestSeen(now) < trialEndMillis

    /** Whole days remaining, rounded up (so "2.3 days left" reads as 3), 0 once expired. */
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
