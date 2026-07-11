package com.tacmap.sync

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end crypto for unit sync. A unit shares a join code; each device
 * derives three values via ONE expensive password-stretch:
 *
 *   master     = PBKDF2-HMAC-SHA256(joinCode, SALT, PBKDF2_ITERATIONS) - 32 B
 *   roomId     = base64url(HMAC(master, "...roomid..."))  - routing id (relay-visible)
 *   roomKey    = HMAC(master, "...roomkey...")             - AES-256-GCM key (never sent)
 *   authToken  = base64url(HMAC(master, "...auth..."))    - writer-auth bearer token
 *
 * Password stretching (PBKDF2) is the whole point: a human-memorable join
 * code ("bravo-tonight") is otherwise trivially brute-forceable offline
 * against retained ciphertext. B/c roomId is downstream of the same 210k-iter
 * PBKDF2, it's no longer a cheap offline verifier for the join code, and its
 * unguessable (256-bit) without the code. authToken only travels in the
 * WebSocket handshake header, never in URL/logs, so a leaked roomId alone
 * can't write to a room.
 *
 * Objects sealed AES-256-GCM with routing metadata bound as AEAD associated
 * data so a hostile relay can't move a blob to a different id/version.
 * Wire blob = iv(12) || ciphertext || tag(16), byte-identical to iOS
 * CryptoKit AES.GCM.SealedBox.combined. Pure java.* so it tests on host JVM.
 *
 * NOTE: join codes assumed ASCII (in-app generator produces ASCII) so the
 * PBKDF2 char->byte encoding matches iOS UTF-8 byte encoding.
 */
object SyncCrypto {
    private const val SALT = "tacmap-sync-salt-v2"
    private const val PBKDF2_ITERATIONS = 210_000
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    // Join-code generation + strength floor. The whole scheme's confidentiality
    // rests on code entropy (roomId is relay-visible and code-derived), so make
    // strong codes the easy path and reject the shortest guessable ones.
    const val MIN_JOIN_CODE_LEN = 14
    private const val GENERATED_CODE_LEN = 16
    private const val CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"  // base32 minus 0/O 1/I/L U

    class RoomKeys(val roomId: String, val roomKey: ByteArray, val authToken: String)

    /** Run the expensive PBKDF2 once, derive all three room values. */
    fun deriveRoom(joinCode: String): RoomKeys {
        val m = master(joinCode)
        return RoomKeys(
            roomId = urlB64(subKey(m, "tacmap-roomid-v2")),
            roomKey = subKey(m, "tacmap-roomkey-v2"),
            authToken = urlB64(subKey(m, "tacmap-auth-v2"))
        )
    }

    fun roomId(joinCode: String): String = deriveRoom(joinCode).roomId
    fun roomKey(joinCode: String): ByteArray = deriveRoom(joinCode).roomKey
    fun authToken(joinCode: String): String = deriveRoom(joinCode).authToken

    /**
     * A strong, unambiguous join code (~78 bits) - the recommended way to start
     * a room. Security rests ENTIRELY on join-code entropy: because roomId is
     * relay-visible and derived from the same code, a coerced relay can offline-
     * guess a weak/human code ("bravo-tonight") and recover roomKey + authToken
     * (see THREAT_MODEL). Uppercase base32 minus look-alikes so it survives being
     * read out over the net.
     */
    fun generateJoinCode(): String {
        val rnd = SecureRandom()
        return buildString {
            repeat(GENERATED_CODE_LEN) { append(CODE_ALPHABET[rnd.nextInt(CODE_ALPHABET.length)]) }
        }
    }

    /** True when a code is too short to resist offline guessing against the
     *  relay-visible roomId. Generated codes clear this comfortably. */
    fun isJoinCodeTooWeak(code: String): Boolean = code.trim().length < MIN_JOIN_CODE_LEN

    private fun master(joinCode: String): ByteArray {
        val spec = PBEKeySpec(joinCode.toCharArray(), SALT.toByteArray(Charsets.UTF_8), PBKDF2_ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun subKey(master: ByteArray, label: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(master, "HmacSHA256"))
        return mac.doFinal(label.toByteArray(Charsets.UTF_8))
    }

    private fun urlB64(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** AEAD associated data, binds ciphertext to its routing metadata. */
    fun aad(id: String, v: Long, kind: String): ByteArray =
        "$id|$v|$kind".toByteArray(Charsets.UTF_8)

    /** Seal plaintext -> iv || ct || tag, authenticating [aad]. */
    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plaintext)
    }

    /** Open iv || ct || tag -> plaintext, null on tamper / wrong key / AAD mismatch. */
    fun open(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray? = try {
        if (blob.size <= IV_LEN) null else {
            val iv = blob.copyOfRange(0, IV_LEN)
            val ct = blob.copyOfRange(IV_LEN, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(ct)
        }
    } catch (_: Throwable) {
        null
    }

    /** wire helpers for the base64 ct field */
    fun encodeBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun decodeBase64(s: String): ByteArray = Base64.getDecoder().decode(s)
}
