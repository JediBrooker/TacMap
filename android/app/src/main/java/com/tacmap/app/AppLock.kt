package com.tacmap.app

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Optional 4-digit PIN lock for the app. Just a deterrent for lost/borrowed
 * devices, NOT real at-rest OPSEC. We never store the PIN itself - just a
 * random salt + stretched SHA-256 hash (120k rounds) so the tiny keyspace
 * isn't trivially recoverable. Lives in app-private prefs with
 * `allowBackup=false` (manifest) so it stays off cloud backups.
 *
 * Escalating lockout on wrong guesses, and you need the current PIN to
 * change or disable the lock so it can't be silently nuked.
 *
 * PIN-only for now; biometric is a follow-up (needs androidx.biometric).
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
            .remove(KEY_FAILS)
            .remove(KEY_LOCKED_UNTIL)
            .apply()
    }

    /** Change PIN. Returns false if current PIN is wrong or we're in lockout. */
    fun changePin(currentPin: String, newPin: String): Boolean {
        if (!verify(currentPin)) return false
        setPin(newPin)
        return true
    }

    /** Turn off the lock. Needs current PIN, returns false if wrong. */
    fun disable(currentPin: String): Boolean {
        if (!isEnabled) { clearAll(); return true }
        if (!verify(currentPin)) return false
        clearAll()
        return true
    }

    private fun clearAll() {
        prefs.edit()
            .remove(KEY_SALT).remove(KEY_HASH)
            .remove(KEY_FAILS).remove(KEY_LOCKED_UNTIL)
            .apply()
    }

    /** Ms left on current lockout, 0 if good to go. */
    fun lockoutRemainingMs(): Long {
        val until = prefs.getLong(KEY_LOCKED_UNTIL, 0L)
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun verify(pin: String): Boolean {
        if (lockoutRemainingMs() > 0L) return false // still locked out, bail
        val salt = prefs.getString(KEY_SALT, null)?.fromHex() ?: return false
        val stored = prefs.getString(KEY_HASH, null)?.fromHex() ?: return false
        return if (constantTimeEquals(hash(pin, salt), stored)) {
            prefs.edit().remove(KEY_FAILS).remove(KEY_LOCKED_UNTIL).apply()
            true
        } else {
            registerFailure()
            false
        }
    }

    private fun registerFailure() {
        val fails = prefs.getInt(KEY_FAILS, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAILS, fails)
        if (fails >= FREE_ATTEMPTS) {
            val idx = (fails - FREE_ATTEMPTS).coerceAtMost(LOCKOUT_LADDER_MS.size - 1)
            editor.putLong(KEY_LOCKED_UNTIL, System.currentTimeMillis() + LOCKOUT_LADDER_MS[idx])
        }
        editor.apply()
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
        const val KEY_FAILS = "fails.v1"
        const val KEY_LOCKED_UNTIL = "lockeduntil.v1"
        const val ITERATIONS = 120_000
        const val FREE_ATTEMPTS = 5
        val LOCKOUT_LADDER_MS = longArrayOf(30_000, 60_000, 300_000, 900_000, 3_600_000)
    }
}
