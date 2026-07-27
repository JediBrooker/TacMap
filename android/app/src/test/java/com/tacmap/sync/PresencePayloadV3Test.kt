package com.tacmap.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PresencePayloadV3Test {

    private val fixture: JsonObject by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val file = File(dir, "testdata/sync_protocol_v3.json")
            if (file.exists()) return@lazy Json.parseToJsonElement(file.readText()).jsonObject
            dir = dir?.parentFile
        }
        error("Could not locate testdata/sync_protocol_v3.json")
    }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    @Test
    fun exactEnvelopeVerifiesSharedPresenceVectorWithoutReserializing() {
        val signed = fixture["signed_preimage"]!!.jsonObject
        val presence = signed["cases"]!!.jsonArray
            .map { it.jsonObject }
            .single { it.str("name") == "presence_update" }
        val plaintext = presence.str("plaintext").toByteArray(Charsets.UTF_8)
        val exact = PresencePayloadV3.decodeCanonicalStandardBase64(
            SyncCrypto.encodeBase64(plaintext)
        )

        assertNotNull(exact)
        assertArrayEquals(plaintext, exact!!.bytes)
        assertEquals(
            presence.str("payload_hash_hex"),
            SyncIdentity.bytesToHex(SyncIdentity.sha256(exact.bytes))
        )

        val keyDerivation = fixture["key_derivation"]!!.jsonObject
        val preimage = SyncIdentity.buildPreimage(
            SyncIdentity.DOMAIN_PRESENCE,
            SyncIdentity.hexToBytes(keyDerivation.str("room_id_raw_hex")),
            presence.str("actor_id"),
            SyncIdentity.hexToBytes(signed.str("session_domain_hex")),
            VersionStamp.counterHex16(presence["counter"]!!.jsonPrimitive.content.toLong()),
            "",
            "loc",
            SyncIdentity.sha256(exact.bytes),
        )
        val publicKey = fixture["identity"]!!.jsonObject["device_a"]!!.jsonObject
            .str("pubkey_base64url")
        assertTrue(SyncSigning.verify(publicKey, preimage, presence.str("signature_base64url")))
    }

    @Test
    fun awkwardDoublesKeepTheEmbeddedBytesAsTheSignatureAuthority() {
        val senderBytes = """
            {"lat":-35.307499999999997,"lon":149.12440000000001,"heading":0,"speed":0,"callsign":"","affiliation":"FRIEND","echelon":"TEAM","function":"INFANTRY","isHQ":false}
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val decoded = PresencePayloadV3.decodeCanonicalStandardBase64(
            SyncCrypto.encodeBase64(senderBytes)
        )

        assertNotNull(decoded)
        assertArrayEquals(senderBytes, decoded!!.bytes)
        assertEquals(-35.3075, decoded.value.lat, 0.0)
        assertEquals(149.1244, decoded.value.lon, 0.0)
        assertTrue(decoded.value.callsign.isBlank())
        assertTrue(decoded.value.isValid())

        // Android is free to serialize the parsed values differently. The
        // receiver still verifies senderBytes, never these re-encoded bytes.
        assertFalse(PresencePayloadV3.encode(decoded.value).bytes.contentEquals(senderBytes))
    }

    @Test
    fun blankCallsignRoundTripsAndNonCanonicalBase64IsRejected() {
        val exact = PresencePayloadV3.encode(
            PresencePayloadV3(
                callsign = "",
                affiliation = "FRIEND",
                echelon = "TEAM",
                function = "INFANTRY",
                isHQ = false,
                lat = -35.3,
                lon = 149.1,
                heading = 0.0,
                speed = 0.0,
            )
        )
        val decoded = PresencePayloadV3.decodeCanonicalStandardBase64(exact.standardBase64)
        assertNotNull(decoded)
        assertEquals("", decoded!!.value.callsign)
        assertTrue(decoded.value.isValid())

        assertEquals(
            null,
            PresencePayloadV3.decodeCanonicalStandardBase64(
                exact.standardBase64.trimEnd('=')
            )
        )
        assertEquals(
            null,
            PresencePayloadV3.decodeCanonicalStandardBase64(exact.standardBase64 + "\n")
        )
    }
}
