package com.tacmap.sync

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Sender-signed, backwards-compatible extension to the v1 presence envelope.
 * Older clients ignore these fields and retain locations for only 45 seconds.
 */
internal object PresenceRetentionV3 {
    const val ENVELOPE_VERSION = 1
    const val VERSION_FIELD = "prv"
    const val PAYLOAD_FIELD = "pr"
    const val SIGNATURE_FIELD = "prsig"
    const val SIGNATURE_KIND = "loc-retention"

    data class Advertisement(
        val seconds: Int,
        val signedPayload: ByteArray,
        val signature: String,
    )

    sealed class DecodeResult {
        data object Absent : DecodeResult()
        data object Invalid : DecodeResult()
        data class Valid(val advertisement: Advertisement) : DecodeResult()
    }

    fun decode(envelope: JSONObject): DecodeResult {
        val fields = listOf(VERSION_FIELD, PAYLOAD_FIELD, SIGNATURE_FIELD)
        if (fields.none(envelope::has)) return DecodeResult.Absent

        val version = strictInteger(envelope.opt(VERSION_FIELD)) ?: return DecodeResult.Invalid
        val encoded = envelope.opt(PAYLOAD_FIELD) as? String ?: return DecodeResult.Invalid
        val signature = envelope.opt(SIGNATURE_FIELD) as? String ?: return DecodeResult.Invalid
        if (version != ENVELOPE_VERSION.toLong() || encoded.isEmpty() ||
            encoded.length > 256 || signature.isEmpty()) return DecodeResult.Invalid

        val bytes = runCatching { SyncCrypto.decodeBase64(encoded) }.getOrNull()
            ?: return DecodeResult.Invalid
        if (bytes.isEmpty() || bytes.size > 128 || SyncCrypto.encodeBase64(bytes) != encoded) {
            return DecodeResult.Invalid
        }
        val text = decodeUtf8Strict(bytes) ?: return DecodeResult.Invalid
        val payload = runCatching { JSONObject(text) }.getOrNull() ?: return DecodeResult.Invalid
        if (payload.length() != 1 || !payload.has("ttl")) return DecodeResult.Invalid
        val ttl = strictInteger(payload.opt("ttl")) ?: return DecodeResult.Invalid
        if (ttl < PresenceExpiryPolicy.LIVE_UPDATE_WINDOW_MS / 1_000L ||
            ttl > PresenceExpiryPolicy.ACTIVE_SESSION_LAST_KNOWN_WINDOW_MS / 1_000L) {
            return DecodeResult.Invalid
        }
        return DecodeResult.Valid(Advertisement(ttl.toInt(), bytes, signature))
    }

    fun encodePayload(seconds: Int): ByteArray? {
        if (seconds < PresenceExpiryPolicy.LIVE_UPDATE_WINDOW_MS / 1_000L ||
            seconds > PresenceExpiryPolicy.ACTIVE_SESSION_LAST_KNOWN_WINDOW_MS / 1_000L) return null
        return JSONObject().put("ttl", seconds).toString().toByteArray(Charsets.UTF_8)
    }

    private fun strictInteger(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
}
