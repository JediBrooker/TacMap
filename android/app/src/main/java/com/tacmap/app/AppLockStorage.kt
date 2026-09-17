package com.tacmap.app

import android.annotation.SuppressLint
import android.content.SharedPreferences

internal data class AppLockStorageSnapshot(
    val saltStored: Boolean,
    val hashStored: Boolean,
    val saltHex: String?,
    val hashHex: String?,
    val failures: Int,
    val lockedUntilMs: Long,
)

/** Persistence seam used to test commits that mutate memory and then fail. */
internal interface AppLockStorage {
    fun read(): AppLockStorageSnapshot
    fun persistCredential(saltHex: String?, hashHex: String?): Boolean
    fun persistAttempts(failures: Int, lockedUntilMs: Long): Boolean
}

internal class SharedPreferencesAppLockStorage(
    private val preferences: SharedPreferences,
) : AppLockStorage {
    override fun read(): AppLockStorageSnapshot = AppLockStorageSnapshot(
        saltStored = runCatching { preferences.contains(KEY_SALT) }.getOrDefault(true),
        hashStored = runCatching { preferences.contains(KEY_HASH) }.getOrDefault(true),
        saltHex = runCatching { preferences.getString(KEY_SALT, null) }.getOrNull(),
        hashHex = runCatching { preferences.getString(KEY_HASH, null) }.getOrNull(),
        failures = runCatching { preferences.getInt(KEY_FAILS, 0) }
            .getOrDefault(0)
            .coerceAtLeast(0),
        lockedUntilMs = runCatching { preferences.getLong(KEY_LOCKED_UNTIL, 0L) }
            .getOrDefault(0L)
            .coerceAtLeast(0L),
    )

    @SuppressLint("ApplySharedPref")
    override fun persistCredential(saltHex: String?, hashHex: String?): Boolean {
        val editor = preferences.edit()
        if (saltHex == null || hashHex == null) {
            editor.remove(KEY_SALT).remove(KEY_HASH)
        } else {
            editor.putString(KEY_SALT, saltHex).putString(KEY_HASH, hashHex)
        }
        return editor
            .remove(KEY_FAILS)
            .remove(KEY_LOCKED_UNTIL)
            .commit()
    }

    @SuppressLint("ApplySharedPref")
    override fun persistAttempts(failures: Int, lockedUntilMs: Long): Boolean {
        val editor = preferences.edit()
        if (failures <= 0) editor.remove(KEY_FAILS) else editor.putInt(KEY_FAILS, failures)
        if (lockedUntilMs <= 0L) {
            editor.remove(KEY_LOCKED_UNTIL)
        } else {
            editor.putLong(KEY_LOCKED_UNTIL, lockedUntilMs)
        }
        return editor.commit()
    }

    private companion object {
        const val KEY_SALT = "salt.v1"
        const val KEY_HASH = "hash.v1"
        const val KEY_FAILS = "fails.v1"
        const val KEY_LOCKED_UNTIL = "lockeduntil.v1"
    }
}
