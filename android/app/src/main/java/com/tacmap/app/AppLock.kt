package com.tacmap.app

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Optional app-access lock (4-digit PIN). A deterrent for a lost/borrowed
 * device — NOT full at-rest OPSEC. The PIN is never stored: we keep a random
 * salt plus a stretched SHA-256 hash (120k rounds) so the tiny PIN space isn't
 * trivially recovered from the stored value.
 *
 * PIN-only for now; biometric unlock is a follow-up (needs androidx.biometric).
 */
class AppLock(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("applock", Context.MODE_PRIVATE)

    val isEnabled: Boolean get() = prefs.contains(KEY_HASH)

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_SALT, salt.toHex())
            .putString(KEY_HASH, hash(pin, salt).toHex())
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_SALT).remove(KEY_HASH).apply()
    }

    fun verify(pin: String): Boolean {
        val salt = prefs.getString(KEY_SALT, null)?.fromHex() ?: return false
        val stored = prefs.getString(KEY_HASH, null)?.fromHex() ?: return false
        return constantTimeEquals(hash(pin, salt), stored)
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        var data = salt + pin.toByteArray(Charsets.UTF_8) + salt
        repeat(ITERATIONS) { md.reset(); data = md.digest(data) }
        return data
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.fromHex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val KEY_SALT = "salt.v1"
        const val KEY_HASH = "hash.v1"
        const val ITERATIONS = 120_000
    }
}
