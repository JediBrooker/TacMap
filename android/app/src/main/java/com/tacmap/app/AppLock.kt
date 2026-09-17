package com.tacmap.app

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

internal enum class AppLockConfigurationState { DISABLED, ENABLED, CORRUPT, STORAGE_ERROR }

internal data class AppLockCredentialRecord(
    val state: AppLockConfigurationState,
    val salt: ByteArray? = null,
    val hash: ByteArray? = null,
)

/** Strict, non-throwing decoder for the app-private credential record. */
internal fun decodeAppLockCredentialRecord(
    saltStored: Boolean,
    hashStored: Boolean,
    saltHex: String?,
    hashHex: String?,
): AppLockCredentialRecord {
    if (!saltStored && !hashStored) return AppLockCredentialRecord(AppLockConfigurationState.DISABLED)
    if (!saltStored || !hashStored) return AppLockCredentialRecord(AppLockConfigurationState.CORRUPT)
    val salt = saltHex.decodeExactHexOrNull(16)
        ?: return AppLockCredentialRecord(AppLockConfigurationState.CORRUPT)
    val hash = hashHex.decodeExactHexOrNull(32)
        ?: return AppLockCredentialRecord(AppLockConfigurationState.CORRUPT)
    return AppLockCredentialRecord(AppLockConfigurationState.ENABLED, salt, hash)
}

private fun String?.decodeExactHexOrNull(byteCount: Int): ByteArray? {
    val value = this ?: return null
    if (value.length != byteCount * 2 || value.any { it.digitToIntOrNull(16) == null }) return null
    return runCatching {
        ByteArray(byteCount) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }.getOrNull()
}

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
class AppLock internal constructor(
    private val storage: AppLockStorage,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val newSalt: () -> ByteArray = {
        ByteArray(16).also { SecureRandom().nextBytes(it) }
    },
) {
    constructor(context: Context) : this(
        SharedPreferencesAppLockStorage(
            context.applicationContext.getSharedPreferences("applock", Context.MODE_PRIVATE),
        ),
    )

    private var storageErrorLatched = false
    private var effectiveCredential: AppLockCredentialRecord
    private var failureFloor: Int
    private var lockedUntilFloorMs: Long

    init {
        val snapshot = runCatching { storage.read() }.getOrElse {
            storageErrorLatched = true
            AppLockStorageSnapshot(
                saltStored = true,
                hashStored = true,
                saltHex = null,
                hashHex = null,
                failures = 0,
                lockedUntilMs = 0L,
            )
        }
        effectiveCredential = decodeAppLockCredentialRecord(
            snapshot.saltStored,
            snapshot.hashStored,
            snapshot.saltHex,
            snapshot.hashHex,
        )
        failureFloor = snapshot.failures.coerceAtLeast(0)
        lockedUntilFloorMs = snapshot.lockedUntilMs.coerceAtLeast(0L)
    }

    internal val configurationState: AppLockConfigurationState
        get() = if (storageErrorLatched) {
            AppLockConfigurationState.STORAGE_ERROR
        } else {
            effectiveCredential.state
        }
    val isEnabled: Boolean get() = configurationState != AppLockConfigurationState.DISABLED
    val isCorrupt: Boolean get() = effectiveCredential.state == AppLockConfigurationState.CORRUPT
    val hasStorageError: Boolean get() = storageErrorLatched
    val canAcceptPin: Boolean get() = effectiveCredential.state == AppLockConfigurationState.ENABLED

    @Synchronized
    fun setPin(pin: String): Boolean {
        if (pin.length != 4 || pin.any { it !in '0'..'9' }) return false
        val salt = newSalt()
        if (salt.size != 16) {
            salt.fill(0)
            storageErrorLatched = true
            return false
        }
        val pinHash = hash(pin, salt)
        val candidate = AppLockCredentialRecord(
            state = AppLockConfigurationState.ENABLED,
            salt = salt.copyOf(),
            hash = pinHash.copyOf(),
        )
        var adopted = false
        return try {
            val persisted = runCatching {
                storage.persistCredential(salt.toHex(), pinHash.toHex())
            }.getOrDefault(false)
            if (persisted) {
                replaceEffectiveCredential(candidate)
                adopted = true
                failureFloor = 0
                lockedUntilFloorMs = 0L
                storageErrorLatched = false
                true
            } else {
                // SharedPreferences.commit updates its process-local map before
                // it reports a failed disk write. Never derive the gate from
                // that map: retain the known credential, or arm the newly
                // supplied credential when this was an enable attempt.
                if (effectiveCredential.state == AppLockConfigurationState.DISABLED) {
                    replaceEffectiveCredential(candidate)
                    adopted = true
                }
                storageErrorLatched = true
                false
            }
        } finally {
            if (!adopted) clearCredential(candidate)
            salt.fill(0)
            pinHash.fill(0)
        }
    }

    /** Change PIN. Returns false if current PIN is wrong or we're in lockout. */
    @Synchronized
    fun changePin(currentPin: String, newPin: String): Boolean {
        if (!verify(currentPin)) return false
        return setPin(newPin)
    }

    /** Turn off the lock. Needs current PIN, returns false if wrong. */
    @Synchronized
    fun disable(currentPin: String): Boolean {
        if (effectiveCredential.state == AppLockConfigurationState.DISABLED && !storageErrorLatched) {
            return true
        }
        if (!verify(currentPin)) return false
        val persisted = runCatching { storage.persistCredential(null, null) }.getOrDefault(false)
        if (!persisted) {
            // Keep the verified credential as the effective runtime gate even
            // if the backend's in-memory map was already cleared.
            storageErrorLatched = true
            return false
        }
        replaceEffectiveCredential(AppLockCredentialRecord(AppLockConfigurationState.DISABLED))
        failureFloor = 0
        lockedUntilFloorMs = 0L
        storageErrorLatched = false
        return true
    }

    /** Ms left on current lockout, 0 if good to go. */
    @Synchronized
    fun lockoutRemainingMs(): Long = (lockedUntilFloorMs - nowMs()).coerceAtLeast(0L)

    @Synchronized
    fun verify(pin: String): Boolean {
        if (lockoutRemainingMs() > 0L) return false // still locked out, bail
        if (pin.length != 4 || pin.any { it !in '0'..'9' }) {
            registerFailure()
            return false
        }
        val record = effectiveCredential
        if (record.state != AppLockConfigurationState.ENABLED) return false
        val salt = checkNotNull(record.salt).copyOf()
        val stored = checkNotNull(record.hash).copyOf()
        val candidate = hash(pin, salt)
        return try {
            if (constantTimeEquals(candidate, stored)) {
                failureFloor = 0
                lockedUntilFloorMs = 0L
                if (!runCatching { storage.persistAttempts(0, 0L) }.getOrDefault(false)) {
                    // A correct PIN may unlock this session, but the gate stays
                    // explicitly armed for subsequent lifecycle transitions.
                    storageErrorLatched = true
                }
                true
            } else {
                registerFailure()
                false
            }
        } finally {
            salt.fill(0)
            stored.fill(0)
            candidate.fill(0)
        }
    }

    private fun registerFailure() {
        val fails = if (failureFloor == Int.MAX_VALUE) Int.MAX_VALUE else failureFloor + 1
        failureFloor = fails
        if (fails >= FREE_ATTEMPTS) {
            val idx = (fails - FREE_ATTEMPTS).coerceAtMost(LOCKOUT_LADDER_MS.size - 1)
            val candidate = nowMs().coerceAtMost(Long.MAX_VALUE - LOCKOUT_LADDER_MS[idx]) +
                LOCKOUT_LADDER_MS[idx]
            lockedUntilFloorMs = maxOf(lockedUntilFloorMs, candidate)
        }
        if (!runCatching {
                storage.persistAttempts(failureFloor, lockedUntilFloorMs)
            }.getOrDefault(false)
        ) {
            // The in-process floor was advanced first, so a failed commit can
            // never buy another attempt in this process.
            storageErrorLatched = true
        }
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

    private fun replaceEffectiveCredential(candidate: AppLockCredentialRecord) {
        clearCredential(effectiveCredential)
        effectiveCredential = candidate
    }

    private fun clearCredential(record: AppLockCredentialRecord) {
        record.salt?.fill(0)
        record.hash?.fill(0)
    }

    private companion object {
        const val ITERATIONS = 120_000
        const val FREE_ATTEMPTS = 5
        val LOCKOUT_LADDER_MS = longArrayOf(30_000, 60_000, 300_000, 900_000, 3_600_000)
    }
}
