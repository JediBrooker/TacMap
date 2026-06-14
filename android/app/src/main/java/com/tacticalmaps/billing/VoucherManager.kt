package com.tacticalmaps.billing

import android.content.Context
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import java.security.MessageDigest

/**
 * Offline voucher unlock — Android twin of the iOS VoucherManager.
 * Same salt + SHA-256 hash list, so one set of codes works on both platforms.
 * Redemption persists in prefs AND Block Store, so it survives reinstall.
 */
class VoucherManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var isRedeemed: Boolean
        get() = prefs.getBoolean(KEY_REDEEMED, false)
        private set(value) = prefs.edit().putBoolean(KEY_REDEEMED, value).apply()

    init {
        // Re-seed redemption from Block Store after a reinstall.
        if (!isRedeemed) {
            val req = RetrieveBytesRequest.Builder()
                .setKeys(listOf(KEY_REDEEMED)).build()
            Blockstore.getClient(appContext).retrieveBytes(req)
                .addOnSuccessListener { result ->
                    val v = result.blockstoreDataMap[KEY_REDEEMED]
                        ?.bytes?.toString(Charsets.UTF_8)
                    if (v == "1") isRedeemed = true
                }
        }
    }

    /** Attempt to redeem. Returns true (and persists) on a valid code. */
    fun redeem(code: String): Boolean {
        val normalized = normalize(code)
        if (normalized.isEmpty()) return false
        val hex = sha256Hex(SALT + normalized)
        if (hex !in VALID_HASHES) return false
        isRedeemed = true
        val data = StoreBytesData.Builder()
            .setKey(KEY_REDEEMED)
            .setBytes("1".toByteArray(Charsets.UTF_8))
            .setShouldBackupToCloud(false)
            .build()
        Blockstore.getClient(appContext).storeBytes(data) // best-effort
        return true
    }

    private fun normalize(code: String): String =
        code.uppercase().filter { it.isLetterOrDigit() }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        /** Must match SALT in scripts/generate_vouchers.py and iOS. */
        private const val SALT = "tacmap-voucher-v1"

        /**
         * SHA-256(SALT + normalizedCode), lowercase hex.
         * REPLACE with the output of scripts/generate_vouchers.py
         * (same list as iOS VoucherManager.validHashes).
         */
        private val VALID_HASHES: Set<String> = setOf(
            // "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        )

        private const val PREFS = "entitlement"
        private const val KEY_REDEEMED = "voucher_redeemed"
    }
}
