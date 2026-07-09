package com.tacmap.billing

import android.content.Context
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData

/**
 * Tracks free-trial window. Same sync API as before (isTrialActive /
 * daysRemaining) so MainActivity doesn't need changes beyond optionally
 * awaiting [restoreFromBlockStore] at startup.
 *
 * Parity with iOS Keychain version:
 *  - First-launch timestamp mirrored to Block Store, which survives
 *    uninstall/reinstall on devices w/ Play services. On reinstall prefs
 *    are empty so we re-seed from Block Store - trial does NOT restart.
 *  - Monotonic "latest seen" timestamp blocks the clock-rollback trick:
 *    effective now = max(wall clock, latest seen).
 *  - On de-Googled devices Block Store calls fail silently and we just
 *    fall back to the old prefs-only model.
 *
 * Gradle: implementation("com.google.android.gms:play-services-auth-blockstore:16.4.0")
 */
class TrialManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        if (!prefs.contains(KEY_FIRST_LAUNCH)) {
            // Don't stamp yet, Block Store might have the real first launch
            // from a previous install. Kick off async restore; if it comes
            // back empty (true first install or no Play services) we stamp.
            restoreFromBlockStore()
        }
        touchLatestSeen(System.currentTimeMillis())
    }

    /**
     * Re-seed prefs from Block Store after reinstall, or write first-launch
     * stamp to both if neither has one. Fire and forget - the sync getters
     * below fall back to "now" until it lands, which is at worst briefly
     * generous but not exploitable long-term.
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
                // no Play services / Block Store unavailable, prefs-only fallback
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
            .setShouldBackupToCloud(false) // device-bound, same as Keychain on iOS
            .build()
        Blockstore.getClient(appContext).storeBytes(data) // best-effort
    }

    private fun ByteArray.toLongOrNull(): Long? =
        toString(Charsets.UTF_8).toLongOrNull()

    // ---- clock-rollback guard -------------------------------------------

    /** Bump the high-water mark, returns effective "now". */
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
