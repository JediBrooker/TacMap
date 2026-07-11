package com.tacmap.sync

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.util.Base64

/**
 * Per-device signing identity for unit sync. Each device holds an Ed25519
 * keypair (seed sealed at rest by [SyncManager]); presence updates carry the
 * device's public key + a signature so a room member CANNOT impersonate
 * another established peer's callsign/position, and a coerced relay's replay of
 * an older signed update is caught by the monotonic counter that rides inside
 * the signed content.
 *
 * Standard Ed25519 (RFC 8032) via the low-level Bouncycastle API - byte-for-byte
 * interoperable with iOS CryptoKit `Curve25519.Signing`. Pure functions so the
 * whole thing unit-tests on the host JVM against the RFC test vectors.
 *
 * NOTE: this authenticates *established* peers (trust-on-first-use per clientId).
 * It does not stop a room-key holder from inventing a brand-new fake clientId -
 * room membership is still the trust boundary (see THREAT_MODEL §7).
 */
object SyncSigning {
    /** 32-byte Ed25519 seed for a fresh device identity. */
    fun generateSeed(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** Base64url (no pad) of the public key derived from [seed]. */
    fun publicKey(seed: ByteArray): String =
        urlB64(Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded)

    /** Ed25519 signature over [message], base64url (no pad). */
    fun sign(seed: ByteArray, message: ByteArray): String {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(message, 0, message.size)
        return urlB64(signer.generateSignature())
    }

    /** True iff [signatureB64] is a valid signature of [message] under
     *  [publicKeyB64]. Any malformed input -> false, never throws. */
    fun verify(publicKeyB64: String, message: ByteArray, signatureB64: String): Boolean = try {
        val pub = Ed25519PublicKeyParameters(deB64(publicKeyB64), 0)
        val sig = deB64(signatureB64)
        val verifier = Ed25519Signer()
        verifier.init(false, pub)
        verifier.update(message, 0, message.size)
        verifier.verifySignature(sig)
    } catch (_: Throwable) {
        false
    }

    /**
     * Canonical, serialization-independent bytes that a presence signature
     * covers. Fields are joined by U+001F (unit separator) so they can't run
     * together ambiguously; coordinates use fixed %.6f (US locale) so both
     * platforms - and sender vs receiver - build byte-identical input from the
     * same values. iOS [SyncSigning.presenceMessage] must match exactly.
     */
    fun presenceMessage(
        clientId: String, ts: Long, lat: Double, lon: Double, heading: Double, speed: Double,
        callsign: String, affiliation: String, echelon: String, function: String, isHQ: Boolean
    ): ByteArray {
        fun f(x: Double) = String.format(java.util.Locale.US, "%.6f", x)
        return listOf(
            clientId, ts.toString(), f(lat), f(lon), f(heading), f(speed),
            callsign, affiliation, echelon, function, if (isHQ) "1" else "0"
        ).joinToString("\u001F").toByteArray(Charsets.UTF_8)
    }

    /**
     * Canonical bytes that an object-write signature covers: the routing
     * metadata a receiver reconstructs from the relay record ([id], [v],
     * [kind], [by]) plus the exact plaintext [content] that was sealed. Joined
     * by U+001F like [presenceMessage] so it is serialization-independent and
     * byte-identical to iOS. For a delete, kind is "del" and content is "".
     * Signing [by] and [v] means the relay cannot re-attribute or roll back a
     * write - either change breaks the signature. iOS must match exactly.
     */
    fun objectMessage(id: String, v: Long, kind: String, by: String, content: String): ByteArray =
        listOf(id, v.toString(), kind, by, content).joinToString("\u001F").toByteArray(Charsets.UTF_8)

    private fun urlB64(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun deB64(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
}
