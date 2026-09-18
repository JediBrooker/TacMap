package com.tacmap.util

import com.tacmap.localization.Messages

import com.tacmap.localization.L10n

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
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
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * The at-rest data-encryption key (DEK) for mission data, and where it lives.
 *
 * Shape is DEK-wrapped-by-KEK:
 *
 *   DEK  = 32 random bytes. Encrypts every store. Never written to disk raw.
 *   KEK  = an AES-256 key managed by the Android Keystore provider. Its key
 *          bytes are not exportable through the Keystore API. This code does
 *          not request StrongBox or inspect KeyInfo, so it does not claim that
 *          every supported device provides hardware-backed storage.
 *   disk = base64(iv || ct || tag) of the DEK wrapped under the KEK, parked
 *          in app-private prefs. Its ciphertext, so prefs is fine.
 *
 * The indirection is what makes [setAuthBound] cheap. Flipping the OPSEC
 * toggle re-wraps 32 bytes under a different KEK. It does not have to
 * re-encrypt every waypoint, drawing and track on the device.
 *
 * Two KEKs, and the difference matters a lot, so read this bit:
 *
 *  - DEVICE mode (default). KEK has no user-auth requirement. A copy of the app
 *    files contains a wrapped DEK rather than the plaintext key, but the exact
 *    offline resistance depends on the device's Keystore implementation.
 *    Anything that can run code as our UID may ask the Keystore to unwrap, so
 *    this does NOT defeat a live compromised-device attacker with code execution.
 *
 *  - AUTH mode (opt-in). KEK is generated with setUserAuthenticationRequired,
 *    so the Android Keystore provider requires a recent device credential or
 *    strong biometric before use. Hardware enforcement varies by device and is
 *    not verified here; a fully compromised OS remains outside this guarantee.
 *    Cost is that after process death nothing can read or write mission data
 *    until the user authenticates, including background track recording.
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
    class LockedException : Exception(), com.tacmap.localization.LocalizedMessageFailure {
        override val localizedMessage get() = Messages.displayMissionDataKeyIsLockedAuthenticateToContinueMessage()
        override val message: String get() = localizedMessage.text
    }

    /** The Keystore KEK is gone (lockscreen removed / factory keystore reset). Data is unreadable. */
    class UnrecoverableException(cause: Throwable?) :
        Exception(cause), com.tacmap.localization.LocalizedMessageFailure {
        override val localizedMessage get() = Messages.displayMissionDataKeyWasInvalidatedByADeviceSecurity87bf4b9fMessage()
        override val message: String get() = localizedMessage.text
    }

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS_DEVICE = "tacmap.kek.device.v1"
    private const val ALIAS_AUTH = "tacmap.kek.auth.v1"
    private const val ALIAS_AUTH_MODE_ANCHOR = "tacmap.mode.auth-required.v1"

    private const val PREFS = "datakey"
    private const val KEY_WRAPPED_LEGACY = "wrapped_dek_v1"
    private const val KEY_MODE_LEGACY = "mode_v1"
    private const val KEY_INITIALIZED = "initialized_v2"
    private const val KEY_ACTIVE_SLOT = "active_slot_v2"
    private const val KEY_ACTIVE_SLOT_BACKUP = "active_slot_backup_v2"
    private const val KEY_SENTINEL_REQUIRED = "sentinel_required_v2"
    private const val SLOT_A = "a"
    private const val SLOT_B = "b"

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
        // Upgrade an already-selected AUTH record to the Keystore-anchored
        // protection floor before any store can observe mutable preferences.
        // The old DEVICE material is removed only after the AUTH wrapper is
        // successfully opened and authenticated by key().
        val installedRecord = activeRecord()
        if (!authModeAnchorPresent() && installedRecord?.mode == DataKeyProtectionMode.AUTH) {
            check(establishAuthModeAnchor()) {
                "Could not anchor auth-bound mission-key protection"
            }
            recoverActivePointer()
        }
        if (activeRecord() == null) {
            // A KEK or legacy mode without its wrapped record means state was
            // lost/corrupted. Treat that as unrecoverable; never mint a new DEK
            // that would make existing mission stores look merely corrupt.
            if (hasExistingKek() || prefs().contains(KEY_MODE_LEGACY) || hasMissionArtifacts()) {
                check(commitPreferenceMutation(setOf(KEY_INITIALIZED)) {
                    putBoolean(KEY_INITIALIZED, true)
                } && prefs().getBoolean(KEY_INITIALIZED, false)) {
                    "Could not persist unrecoverable mission-key state"
                }
            }
            if (!prefs().getBoolean(KEY_INITIALIZED, false)) createDek()
        }
    }

    val isAuthBound: Boolean
        get() = authModeAnchorPresent() ||
            activeRecord()?.mode == DataKeyProtectionMode.AUTH

    /** True when a store can read/write right now without a user auth prompt. */
    val isUnlocked: Boolean
        get() = activeRecord()?.let {
            cached != null || it.mode != DataKeyProtectionMode.AUTH
        } == true

    /**
     * The DEK. Throws [LockedException] in AUTH mode when the user hasn't
     * authenticated recently, and [UnrecoverableException] if the KEK is gone.
     */
    @Synchronized
    fun key(): ByteArray {
        cached?.let { return it.copyOf() }
        val record = activeRecord() ?: throw UnrecoverableException(null)
        val recordKek = kek(
            create = false,
            auth = record.mode == DataKeyProtectionMode.AUTH,
        )
        val plain = unwrap(
            Base64.decode(record.wrapped, Base64.NO_WRAP),
            recordKek
        )
        val payload = DataKeyPayload.decode(plain) ?: throw UnrecoverableException(null)
        plain.fill(0)
        val dek = payload.dek
        try {
            validateOrCreateSentinel(dek, allowCreate = !payload.sentinelRequired)
            if (!payload.sentinelRequired) {
                // One-way upgrade. The v2 magic is inside authenticated Keystore
                // ciphertext, so deleting mutable prefs cannot restore migration.
                val upgraded = Base64.encodeToString(
                    wrap(DataKeyPayload.encodeV2(dek), recordKek), Base64.NO_WRAP
                )
                val keys = setOf(wrappedKey(record.slot), KEY_SENTINEL_REQUIRED)
                check(commitPreferenceMutation(keys) {
                    putString(wrappedKey(record.slot), upgraded)
                        .putBoolean(KEY_SENTINEL_REQUIRED, true)
                } && prefs().getString(wrappedKey(record.slot), null) == upgraded &&
                    prefs().getBoolean(KEY_SENTINEL_REQUIRED, false)) {
                    L10n.text("Could not upgrade mission-key payload")
                }
            }
            if (record.mode == DataKeyProtectionMode.AUTH) {
                if (!authModeAnchorPresent()) {
                    check(establishAuthModeAnchor()) {
                        "Could not anchor auth-bound mission-key protection"
                    }
                }
                finalizeAnchoredAuthProtection(record.slot)
            }
            cached?.fill(0)
            cached = dek.copyOf()
            return dek.copyOf()
        } finally {
            dek.fill(0)
        }
    }

    /** Drop the in-memory DEK. AUTH mode will need a fresh auth after this. */
    @Synchronized
    fun lock() {
        com.tacmap.waypoints.CustomSymbolStore.clear()
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
        if (enabled == isAuthBound) {
            // A pre-anchor AUTH record or interrupted cleanup is completed only
            // after its wrapper has been opened with the authenticated KEK.
            if (enabled) key().fill(0)
            return
        }
        if (enabled) enableAuthBound() else disableAuthBound()
    }

    private fun enableAuthBound() {
        val dek = key()
        var completed = false
        try {
            val old = activeRecord() ?: throw UnrecoverableException(null)
            check(old.mode == DataKeyProtectionMode.DEVICE) {
                "Mission-data key is not in DEVICE mode"
            }
            val nextSlot = if (old.slot == SLOT_A) SLOT_B else SLOT_A
            val target = kek(create = true, auth = true)
            val nextWrapped = Base64.encodeToString(
                wrap(DataKeyPayload.encodeV2(dek), target), Base64.NO_WRAP
            )
            val candidateKeys = setOf(wrappedKey(nextSlot), modeKey(nextSlot))
            val result = DataKeyAuthModeTransition.enable(
                object : DataKeyAuthModeTransition.Steps {
                    override fun persistAuthRecord(): Boolean =
                        commitPreferenceMutation(candidateKeys) {
                            putString(wrappedKey(nextSlot), nextWrapped)
                                .putString(
                                    modeKey(nextSlot),
                                    DataKeyProtectionMode.AUTH.persisted,
                                )
                        }

                    override fun readBackAuthRecord(): Boolean =
                        prefs().getString(wrappedKey(nextSlot), null) == nextWrapped &&
                            prefs().getString(modeKey(nextSlot), null) ==
                            DataKeyProtectionMode.AUTH.persisted

                    override fun verifyAuthRecord(): Boolean =
                        verifyWrappedDek(nextWrapped, target, dek)

                    override fun installAuthAnchor(): Boolean = establishAuthModeAnchor()

                    override fun readBackAuthAnchor(): Boolean = authModeAnchorValid()

                    override fun activateAuthRecord(): Boolean =
                        persistActiveSlot(nextSlot)

                    override fun readBackAuthActivation(): Boolean =
                        activePointersEqual(nextSlot) &&
                            activeRecord()?.let {
                                it.slot == nextSlot && it.mode == DataKeyProtectionMode.AUTH
                            } == true

                    override fun removeDeviceRecords(): Boolean =
                        removeObsoleteKeyRecords(nextSlot)

                    override fun readBackDeviceRecordRemoval(): Boolean =
                        obsoleteKeyRecordsRemoved(nextSlot)

                    override fun deleteDeviceKek(): Boolean = deleteKek(ALIAS_DEVICE)

                    override fun readBackDeviceKekDeletion(): Boolean =
                        !keystore().containsAlias(ALIAS_DEVICE)
                }
            )
            check(result.completed) {
                "Could not enable auth-bound mission-key protection at ${result.failedAt}"
            }
            cached?.fill(0)
            cached = dek.copyOf()
            completed = true
        } finally {
            // Once the Keystore anchor exists, keeping a DEVICE-derived cached
            // copy after a failed transition would make failure look weaker
            // than the durable state. Force a fresh authenticated unwrap.
            if (!completed && authModeAnchorPresent()) lock()
            dek.fill(0)
        }
    }

    private fun disableAuthBound() {
        val dek = key() // may throw Locked; caller authenticates before retrying
        try {
            val old = activeRecord() ?: throw UnrecoverableException(null)
            check(old.mode == DataKeyProtectionMode.AUTH) {
                "Mission-data key is not in AUTH mode"
            }
            val nextSlot = if (old.slot == SLOT_A) SLOT_B else SLOT_A
            val target = kek(create = true, auth = false)
            val nextWrapped = Base64.encodeToString(
                wrap(DataKeyPayload.encodeV2(dek), target), Base64.NO_WRAP
            )
            val candidateKeys = setOf(wrappedKey(nextSlot), modeKey(nextSlot))
            val result = DataKeyAuthModeTransition.disable(
                object : DataKeyAuthModeTransition.DisableSteps {
                    override fun persistDeviceRecord(): Boolean =
                        commitPreferenceMutation(candidateKeys) {
                            putString(wrappedKey(nextSlot), nextWrapped)
                                .putString(
                                    modeKey(nextSlot),
                                    DataKeyProtectionMode.DEVICE.persisted,
                                )
                        }

                    override fun readBackDeviceRecord(): Boolean =
                        prefs().getString(wrappedKey(nextSlot), null) == nextWrapped &&
                            prefs().getString(modeKey(nextSlot), null) ==
                            DataKeyProtectionMode.DEVICE.persisted

                    override fun verifyDeviceRecord(): Boolean =
                        verifyWrappedDek(nextWrapped, target, dek)

                    // While the auth anchor exists, this pointer is only a
                    // prepared downgrade; record selection remains AUTH.
                    override fun activateDeviceRecord(): Boolean =
                        persistActiveSlot(nextSlot)

                    override fun readBackDeviceActivation(): Boolean =
                        activePointersEqual(nextSlot)

                    override fun deleteAuthAnchor(): Boolean =
                        deleteKek(ALIAS_AUTH_MODE_ANCHOR)

                    override fun readBackAuthAnchorDeletion(): Boolean =
                        !authModeAnchorPresent()
                }
            )
            check(result.completed) {
                "Could not disable auth-bound mission-key protection at ${result.failedAt}"
            }
            check(activeRecord()?.let {
                it.slot == nextSlot && it.mode == DataKeyProtectionMode.DEVICE
            } == true) {
                "Device-bound mission-data key was not selected"
            }
            cached?.fill(0)
            cached = dek.copyOf()
        } finally {
            dek.fill(0)
        }
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

    private fun commitPreferenceMutation(
        keys: Set<String>,
        mutate: SharedPreferences.Editor.() -> SharedPreferences.Editor,
    ): Boolean = DurablePreferenceCommit.preferences(
        preferences = prefs(),
        keys = keys,
        mutate = mutate,
        publish = {},
    )

    private fun createDek() {
        val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            val deviceKek = kek(create = true)
            // Persist legacy-shaped raw payload first. If power fails before the
            // sentinel exists, the next launch may safely resume this one migration.
            val wrapped = Base64.encodeToString(wrap(dek, deviceKek), Base64.NO_WRAP)
            val initialKeys = setOf(
                wrappedKey(SLOT_A),
                modeKey(SLOT_A),
                KEY_ACTIVE_SLOT,
                KEY_ACTIVE_SLOT_BACKUP,
                KEY_INITIALIZED,
            )
            check(commitPreferenceMutation(initialKeys) {
                putString(wrappedKey(SLOT_A), wrapped)
                    .putString(modeKey(SLOT_A), DataKeyProtectionMode.DEVICE.persisted)
                    .putString(KEY_ACTIVE_SLOT, SLOT_A)
                    .putString(KEY_ACTIVE_SLOT_BACKUP, SLOT_A)
                    .putBoolean(KEY_INITIALIZED, true)
            } && slotRecord(SLOT_A)?.let {
                it.wrapped == wrapped && it.mode == DataKeyProtectionMode.DEVICE
            } == true && activePointersEqual(SLOT_A) &&
                prefs().getBoolean(KEY_INITIALIZED, false)) {
                L10n.text("Could not persist mission-data key")
            }
            writeSentinel(dek)
            val wrappedV2 = Base64.encodeToString(
                wrap(DataKeyPayload.encodeV2(dek), deviceKek), Base64.NO_WRAP
            )
            val sentinelKeys = setOf(wrappedKey(SLOT_A), KEY_SENTINEL_REQUIRED)
            check(commitPreferenceMutation(sentinelKeys) {
                putString(wrappedKey(SLOT_A), wrappedV2)
                    .putBoolean(KEY_SENTINEL_REQUIRED, true)
            } && prefs().getString(wrappedKey(SLOT_A), null) == wrappedV2 &&
                prefs().getBoolean(KEY_SENTINEL_REQUIRED, false)) {
                L10n.text("Could not persist mission-key sentinel state")
            }
            cached?.fill(0)
            cached = dek.copyOf()
        } finally {
            dek.fill(0)
        }
    }

    private fun wrappedKey(slot: String) = "wrapped_dek_v2_$slot"
    private fun modeKey(slot: String) = "mode_v2_$slot"

    private fun activeRecord(): DataKeySlotRecord? = DataKeyModePolicy.select(
        authModeAnchored = authModeAnchorPresent(),
        activeSlot = prefs().getString(KEY_ACTIVE_SLOT, null),
        backupSlot = prefs().getString(KEY_ACTIVE_SLOT_BACKUP, null),
        records = listOfNotNull(slotRecord(SLOT_A), slotRecord(SLOT_B)),
    )

    /** Recover a torn/missing active pointer only when the choice is unambiguous. */
    private fun recoverActivePointer() {
        val selected = activeRecord() ?: return
        if (activePointersEqual(selected.slot)) return
        check(persistActiveSlot(selected.slot) && activePointersEqual(selected.slot)) {
            "Could not recover mission-data key pointer"
        }
    }

    private fun slotRecord(slot: String): DataKeySlotRecord? {
        if (slot != SLOT_A && slot != SLOT_B) return null
        val wrapped = prefs().getString(wrappedKey(slot), null) ?: return null
        val mode = DataKeyProtectionMode.fromPersisted(
            prefs().getString(modeKey(slot), null)
        ) ?: return null
        return DataKeySlotRecord(slot, wrapped, mode)
    }

    private fun migrateLegacyRecord() {
        if (activeRecord() != null) return
        val wrapped = prefs().getString(KEY_WRAPPED_LEGACY, null) ?: return
        val mode = DataKeyProtectionMode.fromPersisted(
            prefs().getString(
                KEY_MODE_LEGACY,
                DataKeyProtectionMode.DEVICE.persisted,
            )
        ) ?: DataKeyProtectionMode.DEVICE
        // A Keystore anchor is a one-way protection floor. A rolled-back legacy
        // DEVICE preference must never be promoted underneath it.
        if (authModeAnchorPresent() && mode != DataKeyProtectionMode.AUTH) return
        val keys = setOf(
            wrappedKey(SLOT_A),
            modeKey(SLOT_A),
            KEY_ACTIVE_SLOT,
            KEY_ACTIVE_SLOT_BACKUP,
            KEY_INITIALIZED,
        )
        check(commitPreferenceMutation(keys) {
            putString(wrappedKey(SLOT_A), wrapped)
                .putString(modeKey(SLOT_A), mode.persisted)
                .putString(KEY_ACTIVE_SLOT, SLOT_A)
                .putString(KEY_ACTIVE_SLOT_BACKUP, SLOT_A)
                .putBoolean(KEY_INITIALIZED, true)
        } && slotRecord(SLOT_A)?.let {
            it.wrapped == wrapped && it.mode == mode
        } == true && activePointersEqual(SLOT_A)) {
            L10n.text("Could not migrate mission-data key record")
        }
    }

    private fun persistActiveSlot(slot: String): Boolean {
        require(slot == SLOT_A || slot == SLOT_B)
        return commitPreferenceMutation(setOf(KEY_ACTIVE_SLOT, KEY_ACTIVE_SLOT_BACKUP)) {
            putString(KEY_ACTIVE_SLOT, slot)
                .putString(KEY_ACTIVE_SLOT_BACKUP, slot)
        }
    }

    private fun activePointersEqual(slot: String): Boolean =
        prefs().getString(KEY_ACTIVE_SLOT, null) == slot &&
            prefs().getString(KEY_ACTIVE_SLOT_BACKUP, null) == slot

    private fun removeObsoleteKeyRecords(activeSlot: String): Boolean {
        require(activeSlot == SLOT_A || activeSlot == SLOT_B)
        val obsoleteSlot = if (activeSlot == SLOT_A) SLOT_B else SLOT_A
        val keys = setOf(
            wrappedKey(obsoleteSlot),
            modeKey(obsoleteSlot),
            KEY_WRAPPED_LEGACY,
            KEY_MODE_LEGACY,
        )
        return commitPreferenceMutation(keys) {
            remove(wrappedKey(obsoleteSlot))
                .remove(modeKey(obsoleteSlot))
                .remove(KEY_WRAPPED_LEGACY)
                .remove(KEY_MODE_LEGACY)
        }
    }

    private fun obsoleteKeyRecordsRemoved(activeSlot: String): Boolean {
        val obsoleteSlot = if (activeSlot == SLOT_A) SLOT_B else SLOT_A
        return !prefs().contains(wrappedKey(obsoleteSlot)) &&
            !prefs().contains(modeKey(obsoleteSlot)) &&
            !prefs().contains(KEY_WRAPPED_LEGACY) &&
            !prefs().contains(KEY_MODE_LEGACY)
    }

    private fun verifyWrappedDek(
        wrapped: String,
        wrappingKey: SecretKey,
        expectedDek: ByteArray,
    ): Boolean {
        val plain = unwrap(Base64.decode(wrapped, Base64.NO_WRAP), wrappingKey)
        val decoded = DataKeyPayload.decode(plain)
        return try {
            decoded?.sentinelRequired == true && decoded.dek.contentEquals(expectedDek)
        } finally {
            plain.fill(0)
            decoded?.dek?.fill(0)
        }
    }

    private fun finalizeAnchoredAuthProtection(authSlot: String) {
        check(authModeAnchorValid()) {
            "Auth-bound mission-key anchor is missing or invalid"
        }
        val record = slotRecord(authSlot)
        check(record?.mode == DataKeyProtectionMode.AUTH) {
            "Auth-bound mission-key record is missing"
        }
        if (activePointersEqual(authSlot) &&
            obsoleteKeyRecordsRemoved(authSlot) &&
            !keystore().containsAlias(ALIAS_DEVICE)
        ) {
            return
        }
        // The AUTH wrapper has already been opened and its DEK sentinel has
        // authenticated. Only now make the preference pointer durable, remove
        // every weaker/legacy wrapper, and finally remove the DEVICE KEK.
        check(persistActiveSlot(authSlot) && activePointersEqual(authSlot)) {
            "Could not activate auth-bound mission-data key"
        }
        check(removeObsoleteKeyRecords(authSlot) && obsoleteKeyRecordsRemoved(authSlot)) {
            "Could not remove device-bound mission-data key record"
        }
        check(deleteKek(ALIAS_DEVICE) && !keystore().containsAlias(ALIAS_DEVICE)) {
            "Could not remove device-bound mission-data KEK"
        }
    }

    private fun sentinelFile() = File(appContext.filesDir, SENTINEL_NAME)

    private fun validateOrCreateSentinel(dek: ByteArray, allowCreate: Boolean) {
        val file = sentinelFile()
        if (file.exists()) {
            val opened = SealedEnvelope.openFile(dek, file.readBytes(), SENTINEL_LABEL)
            if (opened == null || decodeSentinel(opened) == null) {
                throw UnrecoverableException(IllegalStateException(L10n.text("Mission-key sentinel failed authentication")))
            }
            if (!prefs().getBoolean(KEY_SENTINEL_REQUIRED, false)) {
                check(commitPreferenceMutation(setOf(KEY_SENTINEL_REQUIRED)) {
                    putBoolean(KEY_SENTINEL_REQUIRED, true)
                } && prefs().getBoolean(KEY_SENTINEL_REQUIRED, false))
            }
            return
        }
        if (!allowCreate) {
            throw UnrecoverableException(IllegalStateException(L10n.text("Mission-key sentinel is missing")))
        }
        // One-time upgrade for installs created before the sentinel existed.
        writeSentinel(dek)
        check(commitPreferenceMutation(setOf(KEY_SENTINEL_REQUIRED)) {
            putBoolean(KEY_SENTINEL_REQUIRED, true)
        } && prefs().getBoolean(KEY_SENTINEL_REQUIRED, false))
    }

    private fun readSentinelLabels(dek: ByteArray): Set<String> {
        val opened = SealedEnvelope.openFile(dek, sentinelFile().readBytes(), SENTINEL_LABEL)
            ?: throw UnrecoverableException(IllegalStateException(L10n.text("Mission-key sentinel failed authentication")))
        return decodeSentinel(opened)
            ?: throw UnrecoverableException(IllegalStateException(L10n.text("Mission-key sentinel is malformed")))
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
            throw IllegalStateException(L10n.text("Could not atomically persist mission-key sentinel"))
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

    /**
     * Presence of this auth-required Keystore key is the minimum protection
     * mode. Mutable SharedPreferences can cause denial of service, but cannot
     * select a DEVICE wrapper while this alias remains anchored in Keystore.
     */
    private fun authModeAnchorPresent(): Boolean = try {
        keystore().containsAlias(ALIAS_AUTH_MODE_ANCHOR)
    } catch (_: Throwable) {
        // If Keystore state cannot be queried, prefer the stricter mode. key()
        // will then fail closed rather than trying a DEVICE wrapper.
        true
    }

    private fun authModeAnchorValid(): Boolean = runCatching {
        val ks = keystore()
        if (!ks.containsAlias(ALIAS_AUTH_MODE_ANCHOR)) return@runCatching false
        val key = ks.getKey(ALIAS_AUTH_MODE_ANCHOR, null) as? SecretKey
            ?: return@runCatching false
        val factory = SecretKeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val info = factory.getKeySpec(key, KeyInfo::class.java) as? KeyInfo
            ?: return@runCatching false
        info.isUserAuthenticationRequired
    }.getOrDefault(false)

    private fun establishAuthModeAnchor(): Boolean {
        val ks = keystore()
        if (!ks.containsAlias(ALIAS_AUTH_MODE_ANCHOR)) {
            generateKek(ALIAS_AUTH_MODE_ANCHOR, auth = true)
        }
        return authModeAnchorValid()
    }

    private fun deleteKek(alias: String): Boolean {
        val ks = keystore()
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        return true
    }

    private fun hasExistingKek(): Boolean {
        val ks = keystore()
        return ks.containsAlias(ALIAS_DEVICE) ||
            ks.containsAlias(ALIAS_AUTH) ||
            ks.containsAlias(ALIAS_AUTH_MODE_ANCHOR)
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
