package com.tacmap.util

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * At-rest AEAD for on-device mission data. Same primitives as SyncCrypto
 * (AES-256-GCM, iv(12) || ct || tag(16)) so there's one crypto shape to
 * audit across the whole app, but the key comes from [DataKey] instead of
 * a join code.
 *
 * Two envelope shapes:
 *
 *  - Whole-file: MAGIC || iv || ct || tag. The magic is what lets the
 *    reader tell "this is sealed" from "this is a legacy plaintext JSON
 *    file we need to migrate", without a separate marker file that could
 *    drift out of sync with reality.
 *
 *  - One line of an append log: "v1:" + base64(iv || ct || tag). No magic
 *    b/c it'd be 7 bytes of overhead on every GPS fix, and the "v1:" tag
 *    already gives us a version to bump. Legacy plaintext lines start with
 *    '{' which can't be the first char of base64 or of "v1:", so telling
 *    them apart is unambiguous.
 *
 * The [label] gets bound in as AEAD associated data. That stops someone
 * with write access to the app sandbox from swapping drawings.json's
 * ciphertext into waypoints.json: the blob decrypts fine under the key but
 * the AAD won't match so the tag check fails and we quarantine instead of
 * loading the wrong thing.
 *
 * Pure java.* on purpose so it runs on the host JVM in unit tests. Anything
 * that needs the Android Keystore lives in [DataKey].
 */
object SealedEnvelope {

    /** "TMSEAL" + format version. Bump the byte, not the string. */
    private val MAGIC = byteArrayOf(0x54, 0x4D, 0x53, 0x45, 0x41, 0x4C, 0x01)

    /** Prefix on a sealed append-log line. */
    const val LINE_PREFIX = "v1:"

    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    val magicSize: Int get() = MAGIC.size

    /** AEAD associated data. Binds a blob to the store it belongs to. */
    fun aad(label: String): ByteArray = "tacmap-atrest-v1|$label".toByteArray(Charsets.UTF_8)

    /** Core seal: iv || ct || tag. */
    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plaintext)
    }

    /** Core open. Null on tamper / wrong key / wrong label. Never throws. */
    fun open(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray? = try {
        if (blob.size <= IV_LEN) null else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_LEN))
            )
            cipher.updateAAD(aad)
            cipher.doFinal(blob.copyOfRange(IV_LEN, blob.size))
        }
    } catch (_: Throwable) {
        null
    }

    // MARK: whole-file envelope

    fun isSealedFile(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    fun sealFile(key: ByteArray, plaintext: ByteArray, label: String): ByteArray =
        MAGIC + seal(key, plaintext, aad(label))

    /** Null if the magic is missing or the tag check fails. */
    fun openFile(key: ByteArray, blob: ByteArray, label: String): ByteArray? {
        if (!isSealedFile(blob)) return null
        return open(key, blob.copyOfRange(MAGIC.size, blob.size), aad(label))
    }

    // MARK: append-log line envelope

    /** Legacy plaintext NDJSON lines are bare JSON objects. */
    fun isSealedLine(line: String): Boolean = line.startsWith(LINE_PREFIX)

    fun sealLine(key: ByteArray, plaintext: ByteArray, label: String): String =
        LINE_PREFIX + Base64.getEncoder().encodeToString(seal(key, plaintext, aad(label)))

    /** Null on any problem, incl. a half-written final line from a torn append. */
    fun openLine(key: ByteArray, line: String, label: String): ByteArray? {
        if (!isSealedLine(line)) return null
        val raw = runCatching { Base64.getDecoder().decode(line.substring(LINE_PREFIX.length)) }
            .getOrNull() ?: return null
        return open(key, raw, aad(label))
    }
}
