package com.tacmap.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Validates Android v3 protocol implementation against the shared fixture
 * (testdata/sync_protocol_v3.json). If these pass, the Android client will
 * interop byte-for-byte with the relay and iOS.
 */
class SyncProtocolV3Test {

    private val fixture: JsonObject by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val f = File(dir, "testdata/sync_protocol_v3.json")
            if (f.exists()) return@lazy Json.parseToJsonElement(f.readText()).jsonObject
            dir = dir?.parentFile
        }
        error("Could not locate testdata/sync_protocol_v3.json")
    }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    // -- Key derivation --

    @Test
    fun v3KeyDerivationMatchesFixture() {
        val kd = fixture["key_derivation"]!!.jsonObject
        val joinCode = kd.str("join_code")
        val keys = SyncCrypto.deriveRoomV3(joinCode)

        assertEquals(kd.str("room_id"), keys.roomId)
        assertEquals(kd.str("room_id_raw_hex"), SyncIdentity.bytesToHex(keys.roomIdRaw))
        assertEquals(kd.str("room_key_hex"), SyncIdentity.bytesToHex(keys.roomKey))
        assertEquals(kd.str("metadata_key_hex"), SyncIdentity.bytesToHex(keys.metadataKey))
        assertEquals(kd.str("auth_token_base64url"), keys.authToken)
    }

    // -- Actor ID --

    @Test
    fun actorIdMatchesFixture() {
        val kd = fixture["key_derivation"]!!.jsonObject
        val identity = fixture["identity"]!!.jsonObject
        val roomIdRaw = SyncIdentity.hexToBytes(kd.str("room_id_raw_hex"))

        val devA = identity["device_a"]!!.jsonObject
        val pubA = SyncIdentity.hexToBytes(devA.str("pubkey_raw_hex"))
        assertEquals(devA.str("actor_id"), SyncIdentity.actorId(roomIdRaw, pubA))

        val devB = identity["device_b"]!!.jsonObject
        val pubB = SyncIdentity.hexToBytes(devB.str("pubkey_raw_hex"))
        assertEquals(devB.str("actor_id"), SyncIdentity.actorId(roomIdRaw, pubB))
    }

    @Test
    fun crossRoomCorrelationPrevented() {
        val identity = fixture["identity"]!!.jsonObject
        val devA = identity["device_a"]!!.jsonObject
        val cross = identity["cross_room_correlation"]!!.jsonObject

        val pubA = SyncIdentity.hexToBytes(devA.str("pubkey_raw_hex"))
        val altKeys = SyncCrypto.deriveRoomV3(cross.str("alt_join_code"))
        val altActorId = SyncIdentity.actorId(altKeys.roomIdRaw, pubA)

        assertEquals(cross.str("device_a_actor_id_in_alt_room"), altActorId)
        assertNotEquals(devA.str("actor_id"), altActorId)
    }

    // -- Wire object IDs --

    @Test
    fun wireObjectIdMatchesFixture() {
        val wids = fixture["wire_object_ids"]!!.jsonObject
        val metadataKey = SyncIdentity.hexToBytes(wids.str("metadata_key_hex"))
        val cases = wids["cases"]!!.jsonArray

        for (cEl in cases) {
            val c = cEl.jsonObject
            val localUuid = SyncIdentity.hexToBytes(c.str("local_uuid_hex"))
            assertEquals(c.str("wire_object_id"), SyncIdentity.wireObjectId(metadataKey, localUuid))
        }
    }

    // -- VersionStamp --

    @Test
    fun versionStampComparisonMatchesFixture() {
        val vs = fixture["version_stamp"]!!.jsonObject
        val cases = vs["comparison_cases"]!!.jsonArray

        for (cEl in cases) {
            val c = cEl.jsonObject
            val a = VersionStamp.parse(c.str("a"))!!
            val b = VersionStamp.parse(c.str("b"))!!
            val winner = c.str("winner")
            if (winner == "a") {
                assertTrue("${c.str("name")}: a should win", a > b)
            } else {
                assertTrue("${c.str("name")}: b should win", b > a)
            }
        }
    }

    @Test
    fun versionStampRoundTrip() {
        val vs = VersionStamp(42, "test-actor-id-here")
        val encoded = vs.encode()
        assertEquals("000000000000002a:test-actor-id-here", encoded)
        val parsed = VersionStamp.parse(encoded)!!
        assertEquals(vs, parsed)
    }

    @Test
    fun versionStampMaxCounter() {
        val vs = VersionStamp(VersionStamp.MAX_COUNTER, "actor")
        assertEquals("7fffffffffffffff:actor", vs.encode())
        val parsed = VersionStamp.parse(vs.encode())!!
        assertEquals(VersionStamp.MAX_COUNTER, parsed.counter)
    }

    @Test
    fun versionStampParseBadInput() {
        assertNull(VersionStamp.parse(""))
        assertNull(VersionStamp.parse("not-a-stamp"))
        assertNull(VersionStamp.parse("0000000000000001"))
        assertNull(VersionStamp.parse("000000000000001:x"))
        assertNull(VersionStamp.parse("00000000000000001:x"))
        assertNull(VersionStamp.parse("000000000000000g:x"))
    }

    // -- Signed preimage --

    @Test
    fun signedPreimageMatchesFixture() {
        val sp = fixture["signed_preimage"]!!.jsonObject
        val kd = fixture["key_derivation"]!!.jsonObject
        val roomIdRaw = SyncIdentity.hexToBytes(kd.str("room_id_raw_hex"))
        val sessionDomain = SyncIdentity.hexToBytes(sp.str("session_domain_hex"))
        val cases = sp["cases"]!!.jsonArray

        for (cEl in cases) {
            val c = cEl.jsonObject
            val domainByte = Integer.decode(c.str("domain_byte")).toByte()
            val actorId = c.str("actor_id")
            val counter = c["counter"]!!.jsonPrimitive.long
            val objectId = c.str("object_id")
            val kind = c.str("kind")
            val payloadHash = SyncIdentity.hexToBytes(c.str("payload_hash_hex"))

            val preimage = SyncIdentity.buildPreimage(
                domainByte, roomIdRaw, actorId, sessionDomain,
                VersionStamp.counterHex16(counter), objectId, kind, payloadHash)

            assertEquals(
                "${c.str("name")}: preimage mismatch",
                c.str("preimage_hex"),
                SyncIdentity.bytesToHex(preimage)
            )
        }
    }

    @Test
    fun signatureVerifiesAgainstFixture() {
        val sp = fixture["signed_preimage"]!!.jsonObject
        val identity = fixture["identity"]!!.jsonObject
        val devA = identity["device_a"]!!.jsonObject
        val pubKeyB64 = devA.str("pubkey_base64url")
        val cases = sp["cases"]!!.jsonArray

        for (cEl in cases) {
            val c = cEl.jsonObject
            val preimageBytes = SyncIdentity.hexToBytes(c.str("preimage_hex"))
            val sigB64 = c.str("signature_base64url")
            assertTrue(
                "${c.str("name")}: signature should verify",
                SyncSigning.verify(pubKeyB64, preimageBytes, sigB64)
            )
        }
    }

    // -- Auth verification --

    @Test
    fun authVerificationMatchesFixture() {
        val av = fixture["auth_verification"]!!.jsonObject
        val authTokenB64 = av.str("authTokenBase64url")
        val expectedRoomId = av.str("roomId")
        val authTokenRaw = SyncIdentity.urlB64Decode(authTokenB64)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update("tacmap-room-id-v3".toByteArray(Charsets.UTF_8))
        md.update(byteArrayOf(0))
        md.update(authTokenRaw)
        val roomIdRaw = md.digest()
        assertEquals(expectedRoomId, SyncIdentity.urlB64(roomIdRaw))
    }

    // -- Replay state --

    @Test
    fun replayAcceptNewer() {
        val state = SyncReplayState("test-room")
        val existing = VersionStamp.parse("0000000000000003:actorA")!!
        assertTrue(state.advance("obj1", existing))
        val incoming = VersionStamp.parse("0000000000000005:actorA")!!
        assertTrue(state.advance("obj1", incoming))
    }

    @Test
    fun replayRejectOlder() {
        val state = SyncReplayState("test-room")
        assertTrue(state.advance("obj1", VersionStamp.parse("0000000000000005:actorA")!!))
        assertFalse(state.advance("obj1", VersionStamp.parse("0000000000000003:actorA")!!))
    }

    @Test
    fun replayRejectEqualSameActor() {
        val state = SyncReplayState("test-room")
        assertTrue(state.advance("obj1", VersionStamp.parse("0000000000000005:actorA")!!))
        assertFalse(state.advance("obj1", VersionStamp.parse("0000000000000005:actorA")!!))
    }

    @Test
    fun replayAcceptEqualCounterHigherActor() {
        val state = SyncReplayState("test-room")
        assertTrue(state.advance("obj1", VersionStamp.parse("0000000000000005:actorA")!!))
        assertTrue(state.advance("obj1", VersionStamp.parse("0000000000000005:actorB")!!))
    }

    @Test
    fun replayTombstonePersists() {
        val state = SyncReplayState("test-room")
        assertTrue(state.tombstone("obj1", VersionStamp.parse("0000000000000005:actorA")!!))
        assertTrue(state.isTombstoned("obj1"))
        assertFalse(state.advance("obj1", VersionStamp.parse("0000000000000003:actorB")!!))
        assertTrue(state.advance("obj1", VersionStamp.parse("0000000000000007:actorB")!!))
    }

    @Test
    fun counterAdvanceWindowEnforced() {
        val state = SyncReplayState("test-room")
        assertTrue(state.advance("obj1", VersionStamp(100, "actorA")))
        assertTrue(state.advance("obj2", VersionStamp(10099, "actorA")))
        assertFalse(state.advance("obj3", VersionStamp(20100, "actorA")))
        assertTrue(state.advance("obj3", VersionStamp(20099, "actorA")))
    }

    @Test
    fun actorRegistrationRejectsKeySwap() {
        val state = SyncReplayState("test-room")
        assertTrue(state.registerActor("actorA", "pubkey-A"))
        assertTrue(state.registerActor("actorA", "pubkey-A"))
        assertFalse(state.registerActor("actorA", "pubkey-B"))
    }

    @Test
    fun presenceCounterEnforced() {
        val state = SyncReplayState("test-room")
        assertTrue(state.advancePresence("actorA", 1))
        assertTrue(state.advancePresence("actorA", 5))
        assertFalse(state.advancePresence("actorA", 3))
        assertFalse(state.advancePresence("actorA", 5))
        assertTrue(state.advancePresence("actorA", 6))
    }
}
