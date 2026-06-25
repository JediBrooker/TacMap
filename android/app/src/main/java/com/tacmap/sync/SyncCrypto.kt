package com.tacmap.sync

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end crypto for unit sync. A unit shares a high-entropy **join code**;
 * from it each device derives:
 *  - [roomId] — routing only (a hash; the relay sees this, never the key).
 *  - [roomKey] — the AEAD key (HKDF-SHA256), never leaves the device.
 *
 * Objects are sealed with AES-256-GCM: wire blob = `iv(12) ‖ ciphertext ‖ tag(16)`.
 * This layout is byte-identical to iOS CryptoKit's `AES.GCM.SealedBox.combined`,
 * so an Android and an iOS device on the same join code interoperate. Kept pure
 * (java.* only, no android.*) so it unit-tests on the host JVM.
 */
object SyncCrypto {
    private const val SALT = "tacmap-sync-salt-v1"
    private const val INFO = "tacmap-e2e"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** base64url(no-pad) of SHA-256("tacmap-room|" + joinCode). Routing only. */
    fun roomId(joinCode: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("tacmap-room|$joinCode").toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /** 32-byte AES key via HKDF-SHA256 over the join code. */
    fun roomKey(joinCode: String): ByteArray =
        hkdfSha256(
            ikm = joinCode.toByteArray(Charsets.UTF_8),
            salt = SALT.toByteArray(Charsets.UTF_8),
            info = INFO.toByteArray(Charsets.UTF_8),
            length = 32
        )

    /** Seal plaintext → `iv ‖ ct ‖ tag`. */
    fun seal(key: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    /** Open `iv ‖ ct ‖ tag` → plaintext, or null on tamper / wrong key. */
    fun open(key: ByteArray, blob: ByteArray): ByteArray? = try {
        if (blob.size <= IV_LEN) null else {
            val iv = blob.copyOfRange(0, IV_LEN)
            val ct = blob.copyOfRange(IV_LEN, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ct)
        }
    } catch (_: Throwable) {
        null
    }

    /** Wire helpers for the base64 `ct` field. */
    fun encodeBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun decodeBase64(s: String): ByteArray = Base64.getDecoder().decode(s)

    /** HKDF-SHA256 (RFC 5869). length ≤ 32 → a single expand block. */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)                       // extract
        mac.init(SecretKeySpec(prk, "HmacSHA256"))       // expand
        mac.update(info)
        mac.update(0x01)
        return mac.doFinal().copyOfRange(0, length)
    }
}
