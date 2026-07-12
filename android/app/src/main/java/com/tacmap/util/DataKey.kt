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
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
    private const val KEY_WRAPPED_LEGACY = "wrapped_dek_v1"
    private const val KEY_MODE_LEGACY = "mode_v1"
    private const val KEY_INITIALIZED = "initialized_v2"
    private const val KEY_ACTIVE_SLOT = "active_slot_v2"
    private const val KEY_ACTIVE_SLOT_BACKUP = "active_slot_backup_v2"
    private const val KEY_SENTINEL_REQUIRED = "sentinel_required_v2"
    private const val SLOT_A = "a"
    private const val SLOT_B = "b"

    private const val MODE_DEVICE = "device"
    private const val MODE_AUTH = "auth"

    /** How long an auth counts for. Long enough to unwrap right after the prompt. */
    private const val AUTH_VALIDITY_SECONDS = 30

    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val SENTINEL_NAME = ".mission-key-sentinel-v2"
    private const val SENTINEL_LABEL = "mission-key-sentinel-v2"
    private const val SENTINEL_HEADER = "TacticalMaps mission key v2"

    private lateinit var appContext: Context

    /** Unwrapped DEK, held for the process lifetime. Cleared by [lock]. */
    @Volatile private var cached: ByteArray? = null

    /**
     * Call once from Application.onCreate, before any store is constructed.
     * Creates the DEK on first ever run. Does not unwrap in AUTH mode, so this
     * is safe to call before the user has authenticated.
     */
    @Synchronized
    fun install(context: Context) {
        appContext = context.applicationContext
        recoverActivePointer()
        migrateLegacyRecord()
        recoverActivePointer()
        if (activeRecord() == null) {
            // A KEK or legacy mode without its wrapped record means state was
            // lost/corrupted. Treat that as unrecoverable; never mint a new DEK
            // that would make existing mission stores look merely corrupt.
            if (hasExistingKek() || prefs().contains(KEY_MODE_LEGACY) || hasMissionArtifacts()) {
                prefs().edit().putBoolean(KEY_INITIALIZED, true).commit()
            }
            if (!prefs().getBoolean(KEY_INITIALIZED, false)) createDek()
        }
    }

    val isAuthBound: Boolean
        get() = activeRecord()?.mode == MODE_AUTH

    /** True when a store can read/write right now without a user auth prompt. */
    val isUnlocked: Boolean
        get() = activeRecord()?.let { cached != null || it.mode != MODE_AUTH } == true

    /**
     * The DEK. Throws [LockedException] in AUTH mode when the user hasn't
     * authenticated recently, and [UnrecoverableException] if the KEK is gone.
     */
    @Synchronized
    fun key(): ByteArray {
        cached?.let { return it.copyOf() }
        val record = activeRecord() ?: throw UnrecoverableException(null)
        val recordKek = kek(create = false, auth = record.mode == MODE_AUTH)
        val plain = unwrap(
            Base64.decode(record.wrapped, Base64.NO_WRAP),
            recordKek
        )
        val payload = DataKeyPayload.decode(plain) ?: throw UnrecoverableException(null)
        plain.fill(0)
        val dek = payload.dek
        validateOrCreateSentinel(dek, allowCreate = !payload.sentinelRequired)
        if (!payload.sentinelRequired) {
            // One-way upgrade. The v2 magic is inside authenticated Keystore
            // ciphertext, so deleting mutable prefs cannot restore migration.
            val upgraded = Base64.encodeToString(
                wrap(DataKeyPayload.encodeV2(dek), recordKek), Base64.NO_WRAP
            )
            check(prefs().edit()
                .putString(wrappedKey(record.slot), upgraded)
                .putBoolean(KEY_SENTINEL_REQUIRED, true)
                .commit()) { "Could not upgrade mission-key payload" }
        }
        cached = dek
        return dek.copyOf()
    }

    /** Drop the in-memory DEK. AUTH mode will need a fresh auth after this. */
    @Synchronized
    fun lock() {
        cached?.fill(0)
        cached = null
    }

    /**
     * Move the DEK between device-bound and auth-bound KEKs. Files are untouched.
     * In AUTH mode the caller must have authenticated already, otherwise the
     * unwrap of the current DEK throws [LockedException].
     */
    @Synchronized
    fun setAuthBound(enabled: Boolean) {
        if (enabled == isAuthBound) return
        val dek = key() // may throw Locked, caller prompts and retries
        val target = kek(create = true, auth = enabled)
        val old = activeRecord() ?: throw UnrecoverableException(null)
        val nextSlot = if (old.slot == SLOT_A) SLOT_B else SLOT_A
        val nextMode = if (enabled) MODE_AUTH else MODE_DEVICE
        val nextWrapped = Base64.encodeToString(
            wrap(DataKeyPayload.encodeV2(dek), target), Base64.NO_WRAP
        )

        // Two-phase rotation: write and synchronously persist the inactive slot,
        // verify it can be opened, then atomically switch the active pointer.
        // A kill at any point leaves at least one complete record addressable.
        check(prefs().edit()
            .putString(wrappedKey(nextSlot), nextWrapped)
            .putString(modeKey(nextSlot), nextMode)
            .commit()) { "Could not persist rotated mission-data key" }
        val verifiedPlain = unwrap(Base64.decode(nextWrapped, Base64.NO_WRAP), target)
        val verified = DataKeyPayload.decode(verifiedPlain)
            ?: throw UnrecoverableException(null)
        verifiedPlain.fill(0)
        check(verified.sentinelRequired && verified.dek.contentEquals(dek)) {
            "Rotated mission-data key did not verify"
        }
        verified.dek.fill(0)
        check(prefs().edit()
            .putString(KEY_ACTIVE_SLOT, nextSlot)
            .putString(KEY_ACTIVE_SLOT_BACKUP, nextSlot)
            .commit()) {
            "Could not activate rotated mission-data key"
        }
        cached = dek.copyOf()
        // Deliberately retain the previous slot and KEK. The new active slot is
        // authenticated against the DEK sentinel above, but only an actual
        // process restart proves the preference pointer survived a cold start.
        // Retention is encrypted rollback, not a plaintext copy, and avoids a
        // crash-window that could otherwise destroy every mission store.
        dek.fill(0)
    }

    /** Authenticated downgrade ledger stored inside the DEK sentinel. */
    @Synchronized
    fun isStoreSealedOnly(label: String): Boolean {
        val dek = key()
        return readSentinelLabels(dek).contains(label)
    }

    /** Persist before replacing legacy plaintext, so a crash can only fail closed. */
    @Synchronized
    fun markStoreSealedOnly(label: String) {
        require(label.isNotBlank() && label.length <= 256 && '\n' !in label && '\r' !in label)
        val dek = key()
        val labels = readSentinelLabels(dek).toMutableSet()
        if (labels.add(label)) writeSentinel(dek, labels)
        dek.fill(0)
    }

    // MARK: internals

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun createDek() {
        val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val deviceKek = kek(create = true)
        // Persist legacy-shaped raw payload first. If power fails before the
        // sentinel exists, the next launch may safely resume this one migration.
        val wrapped = Base64.encodeToString(wrap(dek, deviceKek), Base64.NO_WRAP)
        check(prefs().edit()
            .putString(wrappedKey(SLOT_A), wrapped)
            .putString(modeKey(SLOT_A), MODE_DEVICE)
            .putString(KEY_ACTIVE_SLOT, SLOT_A)
            .putString(KEY_ACTIVE_SLOT_BACKUP, SLOT_A)
            .putBoolean(KEY_INITIALIZED, true)
            .commit()) { "Could not persist mission-data key" }
        writeSentinel(dek)
        val wrappedV2 = Base64.encodeToString(
            wrap(DataKeyPayload.encodeV2(dek), deviceKek), Base64.NO_WRAP
        )
        check(prefs().edit()
            .putString(wrappedKey(SLOT_A), wrappedV2)
            .putBoolean(KEY_SENTINEL_REQUIRED, true)
            .commit()) {
            "Could not persist mission-key sentinel state"
        }
        cached = dek.copyOf()
        dek.fill(0)
    }

    private data class SlotRecord(val slot: String, val wrapped: String, val mode: String)

    private fun wrappedKey(slot: String) = "wrapped_dek_v2_$slot"
    private fun modeKey(slot: String) = "mode_v2_$slot"

    private fun activeRecord(): SlotRecord? {
        val slot = prefs().getString(KEY_ACTIVE_SLOT, null) ?: return null
        if (slot != SLOT_A && slot != SLOT_B) return null
        val wrapped = prefs().getString(wrappedKey(slot), null) ?: return null
        val mode = prefs().getString(modeKey(slot), null)
            ?.takeIf { it == MODE_DEVICE || it == MODE_AUTH } ?: return null
        return SlotRecord(slot, wrapped, mode)
    }

    /** Recover a torn/missing active pointer only when the choice is unambiguous. */
    private fun recoverActivePointer() {
        if (activeRecord() != null) return
        val backup = prefs().getString(KEY_ACTIVE_SLOT_BACKUP, null)
        if (backup != null && slotRecord(backup) != null) {
            check(prefs().edit().putString(KEY_ACTIVE_SLOT, backup).commit())
            return
        }
        val complete = listOf(SLOT_A, SLOT_B).filter { slotRecord(it) != null }
        if (complete.size == 1) {
            check(prefs().edit()
                .putString(KEY_ACTIVE_SLOT, complete.single())
                .putString(KEY_ACTIVE_SLOT_BACKUP, complete.single())
                .commit())
        }
    }

    private fun slotRecord(slot: String): SlotRecord? {
        if (slot != SLOT_A && slot != SLOT_B) return null
        val wrapped = prefs().getString(wrappedKey(slot), null) ?: return null
        val mode = prefs().getString(modeKey(slot), null)
            ?.takeIf { it == MODE_DEVICE || it == MODE_AUTH } ?: return null
        return SlotRecord(slot, wrapped, mode)
    }

    private fun migrateLegacyRecord() {
        if (activeRecord() != null) return
        val wrapped = prefs().getString(KEY_WRAPPED_LEGACY, null) ?: return
        val mode = prefs().getString(KEY_MODE_LEGACY, MODE_DEVICE)
            ?.takeIf { it == MODE_DEVICE || it == MODE_AUTH } ?: MODE_DEVICE
        check(prefs().edit()
            .putString(wrappedKey(SLOT_A), wrapped)
            .putString(modeKey(SLOT_A), mode)
            .putString(KEY_ACTIVE_SLOT, SLOT_A)
            .putString(KEY_ACTIVE_SLOT_BACKUP, SLOT_A)
            .putBoolean(KEY_INITIALIZED, true)
            .commit()) { "Could not migrate mission-data key record" }
    }

    private fun sentinelFile() = File(appContext.filesDir, SENTINEL_NAME)

    private fun validateOrCreateSentinel(dek: ByteArray, allowCreate: Boolean) {
        val file = sentinelFile()
        if (file.exists()) {
            val opened = SealedEnvelope.openFile(dek, file.readBytes(), SENTINEL_LABEL)
            if (opened == null || decodeSentinel(opened) == null) {
                throw UnrecoverableException(IllegalStateException("Mission-key sentinel failed authentication"))
            }
            if (!prefs().getBoolean(KEY_SENTINEL_REQUIRED, false)) {
                check(prefs().edit().putBoolean(KEY_SENTINEL_REQUIRED, true).commit())
            }
            return
        }
        if (!allowCreate) {
            throw UnrecoverableException(IllegalStateException("Mission-key sentinel is missing"))
        }
        // One-time upgrade for installs created before the sentinel existed.
        writeSentinel(dek)
        check(prefs().edit().putBoolean(KEY_SENTINEL_REQUIRED, true).commit())
    }

    private fun readSentinelLabels(dek: ByteArray): Set<String> {
        val opened = SealedEnvelope.openFile(dek, sentinelFile().readBytes(), SENTINEL_LABEL)
            ?: throw UnrecoverableException(IllegalStateException("Mission-key sentinel failed authentication"))
        return decodeSentinel(opened)
            ?: throw UnrecoverableException(IllegalStateException("Mission-key sentinel is malformed"))
    }

    private fun decodeSentinel(plain: ByteArray): Set<String>? {
        val lines = plain.toString(Charsets.UTF_8).split('\n')
        if (lines.firstOrNull() != SENTINEL_HEADER) return null
        if (lines.drop(1).any { it.isBlank() || it.length > 256 || '\r' in it }) return null
        return lines.drop(1).toSet()
    }

    private fun writeSentinel(dek: ByteArray, labels: Set<String> = emptySet()) {
        val file = sentinelFile()
        val tmp = File(file.parentFile, file.name + ".tmp")
        val plain = (listOf(SENTINEL_HEADER) + labels.sorted()).joinToString("\n").toByteArray(Charsets.UTF_8)
        tmp.outputStream().use { out ->
            out.write(SealedEnvelope.sealFile(dek, plain, SENTINEL_LABEL))
            out.flush()
            (out as? java.io.FileOutputStream)?.fd?.sync()
        }
        val moved = runCatching {
            Files.move(
                tmp.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        }.isSuccess
        if (!moved) {
            tmp.delete()
            throw IllegalStateException("Could not atomically persist mission-key sentinel")
        }
    }

    /** Detect encrypted/legacy mission stores before a missing wrapper can be replaced. */
    private fun hasMissionArtifacts(): Boolean {
        val pdfPrefs = appContext.getSharedPreferences("pdf_session", Context.MODE_PRIVATE)
        if (pdfPrefs.contains("active_pdf") || pdfPrefs.contains("pdf_calibrations")) return true
        val syncPrefs = appContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
        if (syncPrefs.contains("config_sealed") || syncPrefs.contains("device_seed")) return true
        val known = setOf("waypoints.json", "drawings.json", "recording.ndjson", "pdf_sessions.json")
        return appContext.filesDir.walkTopDown().maxDepth(4).any { file ->
            if (!file.isFile || file.name == SENTINEL_NAME || file.name.endsWith(".tmp")) return@any false
            if (file.name in known && file.length() > 0L) return@any true
            if (file.length() < SealedEnvelope.magicSize) return@any false
            runCatching {
                FileInputStream(file).use { input ->
                    val head = ByteArray(SealedEnvelope.magicSize)
                    input.read(head) == head.size && SealedEnvelope.isSealedFile(head)
                }
            }.getOrDefault(false)
        }
    }

    private fun keystore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun hasExistingKek(): Boolean {
        val ks = keystore()
        return ks.containsAlias(ALIAS_DEVICE) || ks.containsAlias(ALIAS_AUTH)
    }

    private fun kek(create: Boolean, auth: Boolean = isAuthBound): SecretKey {
        val alias = if (auth) ALIAS_AUTH else ALIAS_DEVICE
        val ks = keystore()
        try {
            (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        } catch (e: Throwable) {
            if (e.hasCause<UserNotAuthenticatedException>()) throw LockedException()
            if (e.hasCause<KeyPermanentlyInvalidatedException>()) throw UnrecoverableException(e)
            throw e
        }
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
    private fun wrap(dek: ByteArray, kek: SecretKey): ByteArray = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, kek)
        cipher.iv + cipher.doFinal(dek)
    } catch (e: UserNotAuthenticatedException) {
        throw LockedException()
    } catch (e: KeyPermanentlyInvalidatedException) {
        throw UnrecoverableException(e)
    }

    private fun unwrap(blob: ByteArray, kek: SecretKey): ByteArray = try {
        if (blob.size < IV_LEN + 16) throw UnrecoverableException(null)
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

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return true
            current = current.cause
        }
        return false
    }
}
