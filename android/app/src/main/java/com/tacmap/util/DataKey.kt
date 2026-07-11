package com.tacmap.util

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The at-rest data-encryption key (DEK) for mission data, and where it lives.
 *
 * Shape is DEK-wrapped-by-KEK:
 *
 *   DEK  = 32 random bytes. Encrypts every store. Never written to disk raw.
 *   KEK  = an AES-256 key that lives *inside* the Android Keystore. Non
 *          exportable: the TEE will use it on our behalf but won't hand it
 *          over, not even to root.
 *   disk = base64(iv || ct || tag) of the DEK wrapped under the KEK, parked
 *          in app-private prefs. Its ciphertext, so prefs is fine.
 *
 * The indirection is what makes [setAuthBound] cheap. Flipping the OPSEC
 * toggle re-wraps 32 bytes under a different KEK. It does not have to
 * re-encrypt every waypoint, drawing and track on the device.
 *
 * Two KEKs, and the difference matters a lot, so read this bit:
 *
 *  - DEVICE mode (default). KEK has no user-auth requirement. Anything that
 *    can run code as our UID can ask the Keystore to unwrap. So this defeats
 *    offline attacks - disk image, `adb pull`, a backup, a seized locked
 *    handset, a binned device - and it does NOT defeat a live root/jailbreak
 *    attacker with code exec. Being straight about that is the whole point of
 *    THREAT_MODEL section 7.
 *
 *  - AUTH mode (opt-in). KEK is generated with setUserAuthenticationRequired,
 *    so the TEE itself refuses to unwrap without a recent device-credential or
 *    biometric auth. Root doesn't help you: the check is below the OS. Cost is
 *    that after process death nothing can read or write mission data until the
 *    user authenticates, which includes background track recording.
 *
 * We deliberately use a *validity duration* rather than per-use auth. Per-use
 * auth (validity -1) sets setInvalidatedByBiometricEnrollment implicitly, which
 * means enrolling a new fingerprint destroys the KEK and takes every waypoint
 * on the device with it. A time-boxed window keeps enrollment survivable.
 *
 * Still one sharp edge in AUTH mode we can't design away: if the user removes
 * their lockscreen entirely, Android permanently invalidates the KEK and the
 * DEK is gone. [UnrecoverableException] is that case. The settings toggle warns
 * before enabling.
 *
 * We do NOT set setUnlockedDeviceRequired. It sounds like free hardening but it
 * blocks key use while the screen is off, which kills background GPX recording.
 */
object DataKey {

    /** Auth-bound and the user hasn't authenticated recently enough. Recoverable: prompt, retry. */
    class LockedException : Exception("Mission data key is locked. Authenticate to continue.")

    /** The Keystore KEK is gone (lockscreen removed / factory keystore reset). Data is unreadable. */
    class UnrecoverableException(cause: Throwable?) :
        Exception("Mission data key was invalidated by a device security change.", cause)

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS_DEVICE = "tacmap.kek.device.v1"
    private const val ALIAS_AUTH = "tacmap.kek.auth.v1"

    private const val PREFS = "datakey"
    private const val KEY_WRAPPED = "wrapped_dek_v1"
    private const val KEY_MODE = "mode_v1"

    private const val MODE_DEVICE = "device"
    private const val MODE_AUTH = "auth"

    /** How long an auth counts for. Long enough to unwrap right after the prompt. */
    private const val AUTH_VALIDITY_SECONDS = 30

    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private lateinit var appContext: Context

    /** Unwrapped DEK, held for the process lifetime. Cleared by [lock]. */
    @Volatile private var cached: ByteArray? = null

    /**
     * Call once from Application.onCreate, before any store is constructed.
     * Creates the DEK on first ever run. Does not unwrap in AUTH mode, so this
     * is safe to call before the user has authenticated.
     */
    fun install(context: Context) {
        appContext = context.applicationContext
        if (prefs().getString(KEY_WRAPPED, null) == null) createDek()
    }

    val isAuthBound: Boolean
        get() = prefs().getString(KEY_MODE, MODE_DEVICE) == MODE_AUTH

    /** True when a store can read/write right now without a user auth prompt. */
    val isUnlocked: Boolean
        get() = cached != null || !isAuthBound

    /**
     * The DEK. Throws [LockedException] in AUTH mode when the user hasn't
     * authenticated recently, and [UnrecoverableException] if the KEK is gone.
     */
    fun key(): ByteArray {
        cached?.let { return it }
        val wrapped = prefs().getString(KEY_WRAPPED, null)
            ?: throw IllegalStateException("DataKey.install() was never called")
        val dek = unwrap(Base64.decode(wrapped, Base64.NO_WRAP), kek(create = false))
        cached = dek
        return dek
    }

    /** Drop the in-memory DEK. AUTH mode will need a fresh auth after this. */
    fun lock() { cached = null }

    /**
     * Move the DEK between device-bound and auth-bound KEKs. Files are untouched.
     * In AUTH mode the caller must have authenticated already, otherwise the
     * unwrap of the current DEK throws [LockedException].
     */
    fun setAuthBound(enabled: Boolean) {
        if (enabled == isAuthBound) return
        val dek = key() // may throw Locked, caller prompts and retries
        val target = kek(create = true, auth = enabled)
        prefs().edit()
            .putString(KEY_WRAPPED, Base64.encodeToString(wrap(dek, target), Base64.NO_WRAP))
            .putString(KEY_MODE, if (enabled) MODE_AUTH else MODE_DEVICE)
            .apply()
        // Drop the KEK we no longer use so a stale alias can't resurrect an old wrap.
        runCatching { keystore().deleteEntry(if (enabled) ALIAS_DEVICE else ALIAS_AUTH) }
        cached = dek
    }

    // MARK: internals

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun createDek() {
        val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs().edit()
            .putString(KEY_WRAPPED, Base64.encodeToString(wrap(dek, kek(create = true)), Base64.NO_WRAP))
            .putString(KEY_MODE, MODE_DEVICE)
            .apply()
        cached = dek
    }

    private fun keystore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun kek(create: Boolean, auth: Boolean = isAuthBound): SecretKey {
        val alias = if (auth) ALIAS_AUTH else ALIAS_DEVICE
        val ks = keystore()
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw UnrecoverableException(null)
        return generateKek(alias, auth)
    }

    private fun generateKek(alias: String, auth: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .apply {
                if (auth) {
                    setUserAuthenticationRequired(true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setUserAuthenticationParameters(
                            AUTH_VALIDITY_SECONDS,
                            KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
                    }
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    /** Keystore GCM picks its own iv, so we prepend whatever it used. */
    private fun wrap(dek: ByteArray, kek: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, kek)
        return cipher.iv + cipher.doFinal(dek)
    }

    private fun unwrap(blob: ByteArray, kek: SecretKey): ByteArray = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            kek,
            GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_LEN))
        )
        cipher.doFinal(blob.copyOfRange(IV_LEN, blob.size))
    } catch (e: UserNotAuthenticatedException) {
        throw LockedException()
    } catch (e: KeyPermanentlyInvalidatedException) {
        throw UnrecoverableException(e)
    }
}
