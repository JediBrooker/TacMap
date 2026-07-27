package com.tacmap.sync

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * The exact v3 presence bytes covered by the Ed25519 signature.
 *
 * Different JSON implementations do not serialize the same [Double] to the
 * same decimal text. The sender therefore embeds these exact bytes in the
 * encrypted envelope; a receiver hashes the embedded bytes instead of
 * reserializing the parsed values with its own platform JSON implementation.
 */
internal data class PresencePayloadV3(
    val callsign: String,
    val affiliation: String,
    val echelon: String,
    val function: String,
    val isHQ: Boolean,
    val lat: Double,
    val lon: Double,
    val heading: Double,
    val speed: Double,
) {
    fun putFlatFields(target: JSONObject) {
        target.put("affiliation", affiliation)
        target.put("callsign", callsign)
        target.put("echelon", echelon)
        target.put("function", function)
        target.put("heading", heading)
        target.put("isHQ", isHQ)
        target.put("lat", lat)
        target.put("lon", lon)
        target.put("speed", speed)
    }

    fun isValid(): Boolean =
        callsign.codePointCount(0, callsign.length) <= MAX_CALLSIGN_CODE_POINTS &&
            lat.isFinite() && lat in -90.0..90.0 &&
            lon.isFinite() && lon in -180.0..180.0 &&
            heading.isFinite() && speed.isFinite()

    companion object {
        const val ENVELOPE_VERSION = 1
        private const val MAX_CALLSIGN_CODE_POINTS = 64
        private const val MAX_EXACT_PAYLOAD_BYTES = 4 * 1024
        private val REQUIRED_FIELDS = setOf(
            "affiliation", "callsign", "echelon", "function", "heading",
            "isHQ", "lat", "lon", "speed"
        )

        data class Exact(val value: PresencePayloadV3, val bytes: ByteArray) {
            val standardBase64: String get() = SyncCrypto.encodeBase64(bytes)
        }

        /** Serialize the nine signed fields once and retain those exact bytes. */
        fun encode(value: PresencePayloadV3): Exact {
            val json = JSONObject()
            value.putFlatFields(json)
            return Exact(value, json.toString().toByteArray(Charsets.UTF_8))
        }

        /**
         * Decode the embedded signed bytes. Standard Base64 must be canonical:
         * no URL alphabet, whitespace, or alternative padding is accepted.
         */
        fun decodeCanonicalStandardBase64(encoded: String): Exact? {
            if (encoded.isEmpty() || encoded.length > MAX_EXACT_PAYLOAD_BYTES * 2) return null
            val bytes = runCatching { SyncCrypto.decodeBase64(encoded) }.getOrNull() ?: return null
            if (bytes.isEmpty() || bytes.size > MAX_EXACT_PAYLOAD_BYTES ||
                SyncCrypto.encodeBase64(bytes) != encoded) return null
            val text = decodeUtf8Strict(bytes) ?: return null
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
            if (json.length() != REQUIRED_FIELDS.size ||
                REQUIRED_FIELDS.any { !json.has(it) }) return null
            val value = PresencePayloadV3(
                callsign = json.opt("callsign") as? String ?: return null,
                affiliation = json.opt("affiliation") as? String ?: return null,
                echelon = json.opt("echelon") as? String ?: return null,
                function = json.opt("function") as? String ?: return null,
                isHQ = json.opt("isHQ") as? Boolean ?: return null,
                lat = strictDouble(json, "lat") ?: return null,
                lon = strictDouble(json, "lon") ?: return null,
                heading = strictDouble(json, "heading") ?: return null,
                speed = strictDouble(json, "speed") ?: return null,
            )
            return Exact(value, bytes)
        }

        private fun strictDouble(json: JSONObject, key: String): Double? {
            val raw = json.opt(key) as? Number ?: return null
            return raw.toDouble().takeIf { it.isFinite() }
        }

        private fun decodeUtf8Strict(bytes: ByteArray): String? = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
    }
}
