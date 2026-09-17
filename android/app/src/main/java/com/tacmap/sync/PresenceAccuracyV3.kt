package com.tacmap.sync

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Optional, independently signed horizontal-accuracy extension for v3 presence.
 *
 * It intentionally does not change the existing `pv=1` exact payload. Old
 * clients ignore these envelope fields and continue to authenticate the exact
 * nine-field payload; updated clients additionally authenticate these fixed
 * eight little-endian IEEE-754 bytes under [SIGNATURE_KIND].
 */
internal object PresenceAccuracyV3 {
    const val ENVELOPE_VERSION = 1
    const val VERSION_FIELD = "pav"
    const val PAYLOAD_FIELD = "pa"
    const val SIGNATURE_FIELD = "pasig"
    const val SIGNATURE_KIND = "loc-accuracy"

    data class Advertisement(
        val horizontalAccuracyMetres: Double,
        val signedPayload: ByteArray,
        val signature: String,
    )

    sealed interface DecodeResult {
        data object Absent : DecodeResult
        data object Invalid : DecodeResult
        data class Valid(val advertisement: Advertisement) : DecodeResult
    }

    fun encodePayload(horizontalAccuracyMetres: Double): ByteArray? {
        if (!PresenceLocationQuality.isUsableAccuracy(horizontalAccuracyMetres)) return null
        return ByteBuffer.allocate(java.lang.Double.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putDouble(horizontalAccuracyMetres)
            .array()
    }

    fun decode(envelope: JSONObject): DecodeResult {
        val fields = listOf(VERSION_FIELD, PAYLOAD_FIELD, SIGNATURE_FIELD)
        if (fields.none(envelope::has)) return DecodeResult.Absent
        if (fields.any { !envelope.has(it) }) return DecodeResult.Invalid
        val version = strictInteger(envelope.opt(VERSION_FIELD))
        if (version != ENVELOPE_VERSION.toLong()) return DecodeResult.Invalid
        val encoded = envelope.opt(PAYLOAD_FIELD) as? String ?: return DecodeResult.Invalid
        if (encoded.isEmpty() || encoded.length > 64) return DecodeResult.Invalid
        val bytes = runCatching { SyncCrypto.decodeBase64(encoded) }.getOrNull()
            ?: return DecodeResult.Invalid
        if (bytes.size != java.lang.Double.BYTES || SyncCrypto.encodeBase64(bytes) != encoded) {
            return DecodeResult.Invalid
        }
        val accuracy = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).double
        if (!PresenceLocationQuality.isUsableAccuracy(accuracy)) return DecodeResult.Invalid
        val signature = envelope.opt(SIGNATURE_FIELD) as? String ?: return DecodeResult.Invalid
        if (signature.isEmpty()) return DecodeResult.Invalid
        return DecodeResult.Valid(Advertisement(accuracy, bytes, signature))
    }

    private fun strictInteger(value: Any?): Long? = when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        else -> null
    }
}
