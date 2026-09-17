package com.tacmap.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PresenceReliabilityTest {
    private val accuracyFixture: JsonObject by lazy { fixture("presence_accuracy_v3.json") }
    private val protocolFixture: JsonObject by lazy { fixture("sync_protocol_v3.json") }

    @Test
    fun callbacksAreRejectedAcrossSocketOrGenerationRaces() {
        assertTrue(isCurrentSocketCallback(7, 7, true))
        assertFalse(isCurrentSocketCallback(6, 7, true))
        assertFalse(isCurrentSocketCallback(7, 7, false))
    }

    @Test
    fun reconnectBackoffIsExponentialBoundedAndHandshakeResettable() {
        val policy = SyncReconnectBackoff(randomUnit = { 0.5 })
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L),
            List(6) { policy.nextDelayMs() })
        assertEquals(6, policy.attemptCount)
        policy.reset()
        assertEquals(1_000L, policy.nextDelayMs())
    }

    @Test
    fun networkFlapRetainsThenExpiresVisiblyStalePeer() {
        val peer = PresencePeer(
            clientId = "peer", callsign = "11A", affiliation = "friend",
            echelon = "team", function = "infantry", isHQ = false,
            lat = -35.0, lon = 149.0, heading = 0.0, speed = 0.0, ts = 1,
            sessionDomain = "old", retentionWindowMs = 65 * 60 * 1_000L,
            receivedAtUptimeMs = 1_000L,
        )
        val stale = PresenceExpiryPolicy.markedStale(peer, 50_000L)
        assertTrue(stale.isStale)
        assertEquals(50_000L, stale.reconnectGraceStartedAtUptimeMs)
        assertTrue(PresenceExpiryPolicy.shouldRetain(stale, null, 169_999L))
        assertFalse(PresenceExpiryPolicy.shouldRetain(stale, null, 170_001L))
        val retryFailure = PresenceExpiryPolicy.markedStale(stale, 100_000L)
        assertEquals(50_000L, retryFailure.reconnectGraceStartedAtUptimeMs)
    }

    @Test
    fun backgroundRetentionAndForegroundSessionRotationDoNotBlink() {
        val peer = PresencePeer(
            clientId = "peer", callsign = "11A", affiliation = "friend",
            echelon = "team", function = "infantry", isHQ = false,
            lat = -35.0, lon = 149.0, heading = 0.0, speed = 0.0, ts = 1,
            sessionDomain = "background", retentionWindowMs = 60 * 60 * 1_000L,
            receivedAtUptimeMs = 1_000L,
        )
        assertTrue(PresenceExpiryPolicy.shouldRetain(peer, "background", 30 * 60 * 1_000L))
        val rotating = PresenceExpiryPolicy.markedStale(peer, 30 * 60 * 1_000L)
        assertTrue(PresenceExpiryPolicy.shouldRetain(rotating, "foreground", 30 * 60 * 1_000L + 60_000L))

        val ageStale = PresenceExpiryPolicy.withFreshness(peer, 60_000L)
        val disconnected = PresenceExpiryPolicy.markedStale(ageStale, 30 * 60 * 1_000L)
        assertEquals(46_000L, disconnected.staleSinceUptimeMs)
        assertEquals(30 * 60 * 1_000L, disconnected.reconnectGraceStartedAtUptimeMs)
        assertTrue(PresenceExpiryPolicy.shouldRetain(disconnected, null, 31 * 60 * 1_000L))
        assertFalse(PresenceExpiryPolicy.shouldRetain(disconnected, null, 32 * 60 * 1_000L + 1L))

        val restored = PresenceExpiryPolicy.transportRestored(rotating, 30 * 60 * 1_000L + 1_000L)
        assertEquals(null, restored.reconnectGraceStartedAtUptimeMs)
        assertTrue(restored.isStale) // the coordinate is still older than the live window
    }

    @Test
    fun qualityRejectsBadAccuracyAndImplausibleJumpButAllowsRapidMovement() {
        val old = PresenceLocationFix(0.0, 0.0, 10.0, 1_000L)
        assertFalse(PresenceLocationQuality.acceptsJump(
            old, PresenceLocationFix(1.0, 1.0, 10.0, 6_000L), false
        ))
        assertTrue(PresenceLocationQuality.acceptsJump(
            old, PresenceLocationFix(0.005, 0.0, 10.0, 3_000L), false
        ))
        assertFalse(PresenceLocationQuality.acceptsJump(
            old, PresenceLocationFix(0.0, 0.0, 5_000.0, 2_000L), false
        ))
        assertTrue(PresenceLocationQuality.acceptsJump(
            old, PresenceLocationFix(40.0, 120.0, 10.0, 2_000L), true
        ))
    }

    @Test
    fun threeConsistentFixesRecoverFromAPoisonedFirstFixWithoutMovingTheOldMarkerEarly() {
        val poisoned = PresenceLocationFix(0.0, 0.0, 10.0, 1_000L)
        var cluster: PresenceCandidateCluster? = null
        val realFixes = listOf(
            PresenceLocationFix(1.0, 1.0, 8.0, 2_000L),
            PresenceLocationFix(1.00005, 1.00005, 8.0, 3_000L),
            PresenceLocationFix(1.00010, 1.00010, 8.0, 4_000L),
        )
        realFixes.forEachIndexed { index, candidate ->
            val result = PresenceLocationQuality.evaluateJump(
                poisoned,
                candidate,
                cluster,
                allowSimulatorTeleport = false,
            )
            if (index < 2) {
                assertFalse(result.accepted)
                // The manager does not publish a rejected candidate, so the
                // exact last authenticated coordinate remains visible.
                assertEquals(0.0, poisoned.latitude, 0.0)
                assertEquals(0.0, poisoned.longitude, 0.0)
            } else {
                assertTrue(result.accepted)
                assertTrue(result.recoveredFromOutlier)
            }
            cluster = result.nextCluster
        }
    }

    @Test
    fun legacyWireBoundsRejectInvalidCoordinatesAndSpeed() {
        assertTrue(PresenceLocationQuality.hasValidWireValues(-35.0, 149.0, 359.9, 250.0))
        assertFalse(PresenceLocationQuality.hasValidWireValues(91.0, 149.0, 0.0, 0.0))
        assertFalse(PresenceLocationQuality.hasValidWireValues(-35.0, 181.0, 0.0, 0.0))
        assertFalse(PresenceLocationQuality.hasValidWireValues(-35.0, 149.0, 0.0, -1.0))
        assertFalse(PresenceLocationQuality.hasValidWireValues(-35.0, 149.0, 0.0, 1_001.0))
        assertTrue(PresenceLocationQuality.isPlausibleLegacyTimestamp(130_000L, 100_000L))
        assertFalse(PresenceLocationQuality.isPlausibleLegacyTimestamp(130_001L, 100_000L))
    }

    @Test
    fun signedAccuracyMatchesCrossPlatformFixture() {
        val accuracy = accuracyFixture.double("horizontal_accuracy_metres")
        val payload = PresenceAccuracyV3.encodePayload(accuracy)
        assertNotNull(payload)
        assertEquals(accuracyFixture.str("payload_base64"), SyncCrypto.encodeBase64(payload!!))
        assertEquals(accuracyFixture.str("payload_hash_hex"),
            SyncIdentity.bytesToHex(SyncIdentity.sha256(payload)))
        val preimage = SyncIdentity.buildPreimage(
            SyncIdentity.DOMAIN_PRESENCE,
            SyncIdentity.hexToBytes(accuracyFixture.str("room_id_raw_hex")),
            accuracyFixture.str("actor_id"),
            SyncIdentity.hexToBytes(accuracyFixture.str("session_domain_hex")),
            VersionStamp.counterHex16(accuracyFixture.long("counter")),
            "",
            accuracyFixture.str("signature_kind"),
            SyncIdentity.sha256(payload),
        )
        assertEquals(accuracyFixture.str("preimage_hex"), SyncIdentity.bytesToHex(preimage))
        val signature = SyncSigning.sign(
            SyncIdentity.hexToBytes(accuracyFixture.str("seed_hex")), preimage
        )
        assertEquals(accuracyFixture.str("signature_base64url"), signature)
        assertTrue(SyncSigning.verify(accuracyFixture.str("public_key_base64url"), preimage, signature))
        assertTrue(SyncSigning.verify(
            accuracyFixture.str("public_key_base64url"),
            preimage,
            accuracyFixture.str("ios_signature_base64url"),
        ))
    }

    @Test
    fun optionalAccuracyExtensionLeavesOldExactPayloadDecodable() {
        val payload = PresencePayloadV3(
            callsign = "11A", affiliation = "friend", echelon = "team",
            function = "infantry", isHQ = false, lat = -35.0, lon = 149.0,
            heading = 90.0, speed = 1.5,
        )
        val exact = PresencePayloadV3.encode(payload)
        val envelope = JSONObject().apply {
            put("pv", PresencePayloadV3.ENVELOPE_VERSION)
            put("p", exact.standardBase64)
            put(PresenceAccuracyV3.VERSION_FIELD, PresenceAccuracyV3.ENVELOPE_VERSION)
            put(PresenceAccuracyV3.PAYLOAD_FIELD, accuracyFixture.str("payload_base64"))
            put(PresenceAccuracyV3.SIGNATURE_FIELD, accuracyFixture.str("signature_base64url"))
        }
        // This is the unchanged old-client decoding path: it reads only pv/p.
        val decoded = PresencePayloadV3.decodeCanonicalStandardBase64(envelope.getString("p"))
        assertEquals(payload, decoded?.value)
        assertTrue(PresenceAccuracyV3.decode(envelope) is PresenceAccuracyV3.DecodeResult.Valid)
    }

    @Test
    fun optionalAccuracyRejectsEveryPartialOrNonCanonicalPermutation() {
        val fields = listOf(
            PresenceAccuracyV3.VERSION_FIELD,
            PresenceAccuracyV3.PAYLOAD_FIELD,
            PresenceAccuracyV3.SIGNATURE_FIELD,
        )
        fields.forEach { only ->
            val partial = JSONObject().put(only, when (only) {
                PresenceAccuracyV3.VERSION_FIELD -> PresenceAccuracyV3.ENVELOPE_VERSION
                PresenceAccuracyV3.PAYLOAD_FIELD -> accuracyFixture.str("payload_base64")
                else -> "signature"
            })
            assertTrue(PresenceAccuracyV3.decode(partial) is PresenceAccuracyV3.DecodeResult.Invalid)
        }
        fun envelope(version: Any = 1, payload: String = accuracyFixture.str("payload_base64"),
                     signature: Any = "signature") = JSONObject()
            .put(PresenceAccuracyV3.VERSION_FIELD, version)
            .put(PresenceAccuracyV3.PAYLOAD_FIELD, payload)
            .put(PresenceAccuracyV3.SIGNATURE_FIELD, signature)

        assertTrue(PresenceAccuracyV3.decode(envelope(true)) is PresenceAccuracyV3.DecodeResult.Invalid)
        assertTrue(PresenceAccuracyV3.decode(envelope(1.0)) is PresenceAccuracyV3.DecodeResult.Invalid)
        assertTrue(PresenceAccuracyV3.decode(envelope(payload = "not/base64")) is PresenceAccuracyV3.DecodeResult.Invalid)
        assertTrue(PresenceAccuracyV3.decode(envelope(payload = SyncCrypto.encodeBase64(ByteArray(7)))) is PresenceAccuracyV3.DecodeResult.Invalid)
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, 1_001.0).forEach { invalid ->
            val bytes = java.nio.ByteBuffer.allocate(8)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putDouble(invalid)
                .array()
            assertTrue(PresenceAccuracyV3.decode(
                envelope(payload = SyncCrypto.encodeBase64(bytes))
            ) is PresenceAccuracyV3.DecodeResult.Invalid)
        }
        assertTrue(PresenceAccuracyV3.decode(envelope(signature = "")) is PresenceAccuracyV3.DecodeResult.Invalid)
        assertTrue(PresenceAccuracyV3.decode(envelope(signature = 7)) is PresenceAccuracyV3.DecodeResult.Invalid)
    }

    @Test
    fun oldExactPresenceFixtureStillDecodesAndVerifiesWithoutTheAccuracyExtension() {
        val signed = protocolFixture["signed_preimage"]!!.jsonObject
        val presence = signed["cases"]!!.jsonArray
            .map { it.jsonObject }
            .first { it.str("name") == "presence_update" }
        val device = protocolFixture["identity"]!!.jsonObject["device_a"]!!.jsonObject
        val keys = protocolFixture["key_derivation"]!!.jsonObject
        val bytes = presence.str("plaintext").toByteArray(Charsets.UTF_8)
        val decoded = PresencePayloadV3.decodeCanonicalStandardBase64(
            SyncCrypto.encodeBase64(bytes)
        )
        assertNotNull(decoded)
        assertEquals(presence.str("payload_hash_hex"),
            SyncIdentity.bytesToHex(SyncIdentity.sha256(decoded!!.bytes)))
        val preimage = SyncIdentity.buildPreimage(
            SyncIdentity.DOMAIN_PRESENCE,
            SyncIdentity.hexToBytes(keys.str("room_id_raw_hex")),
            presence.str("actor_id"),
            SyncIdentity.hexToBytes(signed.str("session_domain_hex")),
            VersionStamp.counterHex16(presence.long("counter")),
            "",
            "loc",
            SyncIdentity.sha256(decoded.bytes),
        )
        assertEquals(presence.str("preimage_hex"), SyncIdentity.bytesToHex(preimage))
        assertTrue(SyncSigning.verify(
            device.str("pubkey_base64url"),
            preimage,
            presence.str("signature_base64url"),
        ))
    }

    @Test
    fun explicitLeaveProofIsBoundToTheExactHelloSession() {
        val signed = protocolFixture["signed_preimage"]!!.jsonObject
        val device = protocolFixture["identity"]!!.jsonObject["device_a"]!!.jsonObject
        val keys = protocolFixture["key_derivation"]!!.jsonObject
        val actor = device.str("actor_id")
        val session = SyncIdentity.hexToBytes(signed.str("session_domain_hex"))
        val helloVersion = "0000000000000001:$actor"
        val preimage = SyncIdentity.explicitLeavePreimage(
            SyncIdentity.hexToBytes(keys.str("room_id_raw_hex")),
            actor,
            session,
            helloVersion,
        )!!
        val signature = SyncSigning.sign(
            SyncIdentity.hexToBytes(device.str("seed_hex")),
            preimage,
        )
        assertTrue(SyncSigning.verify(device.str("pubkey_base64url"), preimage, signature))
        val otherSession = session.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val otherPreimage = SyncIdentity.explicitLeavePreimage(
            SyncIdentity.hexToBytes(keys.str("room_id_raw_hex")),
            actor,
            otherSession,
            helloVersion,
        )!!
        assertFalse(SyncSigning.verify(device.str("pubkey_base64url"), otherPreimage, signature))
    }

    private fun fixture(name: String): JsonObject {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val file = File(dir, "testdata/$name")
            if (file.exists()) return Json.parseToJsonElement(file.readText()).jsonObject
            dir = dir?.parentFile
        }
        error("Could not locate testdata/$name")
    }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
    private fun JsonObject.double(key: String): Double = this[key]!!.jsonPrimitive.content.toDouble()
    private fun JsonObject.long(key: String): Long = this[key]!!.jsonPrimitive.content.toLong()
}
